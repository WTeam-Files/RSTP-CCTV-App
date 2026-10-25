package com.zektopic.cctvapp

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

class WebServer(
    private val context: Context,
    private val ipAddress: String,
    private val imageProvider: () -> ByteArray?,
    private val onSwitchCamera: () -> Unit,
    private val onStartStream: () -> Unit,
    private val onStopStream: () -> Unit,
    private val isStreaming: () -> Boolean,
    private val onCodecUpdate: (String) -> Unit,
    private val getCurrentCodec: () -> String,
    /** What the encoder actually negotiated; differs from the request after a fallback. */
    private val getActiveCodec: () -> String,
    private val onResolutionUpdate: (Int, Int) -> Unit,
    private val getCurrentResolution: () -> String,
    private val getCameraRotation: () -> Int,
    private val getAuthEnabled: () -> Boolean,
    private val getUsername: () -> String,
    private val getPassword: () -> String,
    // New setting callbacks
    private val onSettingUpdate: (String, String) -> Unit,
    private val getShowTimestamp: () -> Boolean,
    private val getShowDate: () -> Boolean,
    private val getTimestampPosition: () -> String,
    private val getTimestampSize: () -> String,
    private val getFlashlightEnabled: () -> Boolean,
    private val getNightModeEnabled: () -> Boolean,
    /** Retained for dashboards and NVR scripts written against the old boolean. */
    private val getForceSoftware: () -> Boolean,
    private val getEncoderImplementation: () -> String,
    /** What the encoder was actually built with, after any device-capability fallback. */
    private val getActiveEncoderImplementation: () -> String,
    /** Pre-rendered JSON object of what this device can encode. */
    private val getCodecSupportJson: () -> String,
    private val getAdaptiveQualityEnabled: () -> Boolean,
    /** PowerManager.THERMAL_STATUS_*; always 0 below API 29. */
    private val getThermalStatus: () -> Int,
    /** Percent of the configured bitrate currently in use; 100 when unrestricted. */
    private val getQualityScalePercent: () -> Int,
    /** NONE, THERMAL or BATTERY. */
    private val getQualityTrigger: () -> String,
    private val getActiveSnapshotIntervalMs: () -> Int,
    private val getIdleSnapshotIntervalMs: () -> Int,
    private val getAnalysisWidth: () -> Int,
    private val getSnapshotJpegQuality: () -> Int,
    /** Hand-pinned bitrate in kbit/s, or 0 for auto. */
    private val getBitrateKbps: () -> Int,
    /** What the encoder was actually prepared with, which is what auto resolved to. */
    private val getActiveBitrateKbps: () -> Int,
    private val getVideoFps: () -> Int,
    private val getKeyframeIntervalSeconds: () -> Int,
    private val getShowPreview: () -> Boolean,
    private val getDetectionEnabled: () -> Boolean,
    private val getMotionDetectionEnabled: () -> Boolean,
    private val getObjectDetectionEnabled: () -> Boolean,
    private val getObjectDetectorReady: () -> Boolean,
    private val onAuthUpdate: (Boolean, String, String) -> Unit,
    private val listEventsJson: (Long?, Int) -> String,
    private val getEventJson: (String) -> String?,
    private val getEventSnapshotFile: (String) -> File?,
    private val getEventClipFile: (String) -> File?,
    private val onCreateTestEvent: () -> String,
    private val getBatteryLevel: () -> Int,
    private val getWifiStrength: () -> Int,
    private val getWebAuthEnabled: () -> Boolean,
    /** Wall-clock capture time of the frame /shot.jpg serves, or 0 when unknown. */
    private val getSnapshotCapturedAtMs: () -> Long = { 0L },
    /**
     * Port to bind. Defaults to [PORT]; tests pass [EPHEMERAL_PORT] so they get a free
     * port from the OS instead of colliding with a [CctvServerService] already on 8080.
     * Read the real port back from `listeningPort` after [start].
     */
    port: Int = PORT
) : NanoHTTPD(port) {

    companion object {
        const val PORT = 8080

        /** Bind whatever port the OS hands out. Only used by tests. */
        const val EPHEMERAL_PORT = 0
        private const val REALM = "CCTV Dashboard"

        /**
         * Scripts and styles are same-origin files only, so injected markup cannot run
         * inline script or pull in anything from elsewhere.
         */
        private const val WEB_CSP = "default-src 'self'; img-src 'self' blob: data:; " +
            "media-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; " +
            "form-action 'self'"
    }

    private val startedAtMs: Long = System.currentTimeMillis()

    override fun serve(session: IHTTPSession): Response {
        return try {
            val headers = session.headers ?: emptyMap()

            // Reject cross-site requests before doing any work. Without this, a page on
            // the internet can drive this server from inside the victim's LAN.
            if (!WebAuth.isOriginAllowed(headers["origin"], headers["host"])) {
                return newFixedLengthResponse(
                    Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Cross-origin request rejected"
                )
            }

            if (!WebAuth.isAuthorized(
                    getWebAuthEnabled(), getUsername(), getPassword(), headers["authorization"]
                )
            ) {
                return unauthorized()
            }

            // Mutations should be POSTed. NanoHTTPD only populates `parameters` for a
            // form-encoded body once the body has been parsed, so do that up front and
            // GET/POST become interchangeable for every /action/* handler below.
            if (session.method == Method.POST || session.method == Method.PUT) {
                session.parseBody(HashMap())
            }

            processRequest(session)
        } catch (e: Exception) {
            android.util.Log.e("WebServer", "Request failed: ${session.uri}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal Error")
        }
    }

    private fun unauthorized(): Response {
        val response = newFixedLengthResponse(
            Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Authentication required"
        )
        response.addHeader("WWW-Authenticate", "Basic realm=\"$REALM\", charset=\"UTF-8\"")
        return response
    }

    private fun processRequest(session: IHTTPSession): Response {
        val uri = session.uri

        if (uri == "/shot.jpg") {
            val imageBytes = imageProvider()
            return if (imageBytes != null) {
                val response = newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(imageBytes), imageBytes.size.toLong())
                addFrameAgeHeaders(response, getSnapshotCapturedAtMs())
                response
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Camera not ready")
            }
        }

        if (uri == "/action/switch-camera") {
            onSwitchCamera()
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Switched")
        }
        
        if (uri == "/action/toggle-stream") {
            if (isStreaming()) {
                onStopStream()
                return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Stopped")
            } else {
                onStartStream()
                return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Started")
            }
        }

        if (uri == "/action/set-codec") {
            val codec = session.parameters["codec"]?.get(0) ?: "H264"
            onCodecUpdate(codec)
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Codec Updated")
        }
        
        if (uri == "/action/set-resolution") {
            val w = session.parameters["w"]?.get(0)?.toIntOrNull() ?: 640
            val h = session.parameters["h"]?.get(0)?.toIntOrNull() ?: 480
            onResolutionUpdate(w, h)
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Resolution Updated")
        }

        // Generic setting update endpoint
        if (uri == "/action/set-setting") {
            val key = session.parameters["key"]?.get(0) ?: ""
            val value = session.parameters["value"]?.get(0) ?: ""
            if (key.isNotEmpty()) {
                onSettingUpdate(key, value)
                return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Setting Updated: $key")
            }
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing key")
        }

        // Auth update endpoint
        if (uri == "/action/set-auth") {
            val enabled = session.parameters["enabled"]?.get(0)?.toBoolean() ?: false
            val username = session.parameters["username"]?.get(0) ?: ""
            val password = session.parameters["password"]?.get(0) ?: ""
            onAuthUpdate(enabled, username, password)
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Auth Updated")
        }

        if (uri == "/action/create-test-event") {
            val json = onCreateTestEvent()
            return newFixedLengthResponse(Response.Status.OK, "application/json", json)
        }

        if (uri == "/events") {
            val since = session.parameters["since"]?.firstOrNull()?.toLongOrNull()
            val limit = session.parameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 100
            return newFixedLengthResponse(
                Response.Status.OK, "application/json", listEventsJson(since, limit)
            )
        }

        if (uri.startsWith("/events/")) {
            val parts = uri.trim('/').split('/')
            if (parts.size >= 2) {
                val eventId = parts[1]

                if (parts.size == 2) {
                    val eventJson = getEventJson(eventId)
                    return if (eventJson != null) {
                        newFixedLengthResponse(Response.Status.OK, "application/json", eventJson)
                    } else {
                        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Event not found")
                    }
                }

                if (parts.size == 3 && parts[2] == "snapshot.jpg") {
                    val snapshotFile = getEventSnapshotFile(eventId)
                    return if (snapshotFile != null && snapshotFile.exists()) {
                        newFixedLengthResponse(
                            Response.Status.OK,
                            "image/jpeg",
                            FileInputStream(snapshotFile),
                            snapshotFile.length()
                        )
                    } else {
                        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Snapshot not found")
                    }
                }

                if (parts.size == 3 && parts[2] == "clip.mp4") {
                    val clipFile = getEventClipFile(eventId)
                    return if (clipFile != null && clipFile.exists()) {
                        newFixedLengthResponse(
                            Response.Status.OK,
                            "video/mp4",
                            FileInputStream(clipFile),
                            clipFile.length()
                        )
                    } else {
                        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Clip not found")
                    }
                }
            }
        }

        // JSON status endpoint
        if (uri == "/status") {
            val streaming = isStreaming()
            val codec = getCurrentCodec()
            val resolution = getCurrentResolution()
            val authEnabled = getAuthEnabled()
            val username = getUsername()
            val rtspUrl = buildRtspUrl()
            val json = """{
                "streaming":$streaming,
                "codec":"$codec",
                "activeCodec":"${getActiveCodec()}",
                "resolution":"$resolution",
                "cameraRotation":${getCameraRotation()},
                "authEnabled":$authEnabled,
                "username":"${escapeJson(username)}",
                "rtspUrl":"${escapeJson(rtspUrl)}",
                "showTimestamp":${getShowTimestamp()},
                "showDate":${getShowDate()},
                "timestampPosition":"${getTimestampPosition()}",
                "timestampSize":"${getTimestampSize()}",
                "flashlightEnabled":${getFlashlightEnabled()},
                "nightModeEnabled":${getNightModeEnabled()},
                "forceSoftware":${getForceSoftware()},
                "encoderImplementation":"${getEncoderImplementation()}",
                "activeEncoderImplementation":"${getActiveEncoderImplementation()}",
                "codecSupport":${getCodecSupportJson()},
                "adaptiveQualityEnabled":${getAdaptiveQualityEnabled()},
                "thermalStatus":${getThermalStatus()},
                "qualityScalePercent":${getQualityScalePercent()},
                "qualityTrigger":"${getQualityTrigger()}",
                "activeSnapshotIntervalMs":${getActiveSnapshotIntervalMs()},
                "idleSnapshotIntervalMs":${getIdleSnapshotIntervalMs()},
                "analysisWidth":${getAnalysisWidth()},
                "snapshotJpegQuality":${getSnapshotJpegQuality()},
                "bitrateKbps":${getBitrateKbps()},
                "activeBitrateKbps":${getActiveBitrateKbps()},
                "videoFps":${getVideoFps()},
                "keyframeIntervalSeconds":${getKeyframeIntervalSeconds()},
                "showPreview":${getShowPreview()},
                "detectionEnabled":${getDetectionEnabled()},
                "motionDetectionEnabled":${getMotionDetectionEnabled()},
                "objectDetectionEnabled":${getObjectDetectionEnabled()},
                "objectDetectorReady":${getObjectDetectorReady()},
                "batteryLevel":${getBatteryLevel()},
                "wifiStrength":${getWifiStrength()},
                "webAuthEnabled":${getWebAuthEnabled()},
                "audioEnabled":${AppPreferences.getAudioEnabled(context)}
            }""".trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "application/json", json)
        }

        // Read-only snapshot buffer endpoints. They sit behind the same origin and
        // authorization gate as everything else because they are reached via serve().
        if (uri == "/api/frames") {
            val limit = SnapshotFrames.clampLimit(session.parameters["limit"]?.firstOrNull()?.toIntOrNull())
            val before = session.parameters["before"]?.firstOrNull()?.toLongOrNull()
            val frames = SnapshotFrames(SnapshotBuffer.directoryFor(context))
            val json = SnapshotFrames.framesJson(frames.list(limit, before), frames.stats())
            return jsonResponse(json)
        }

        if (uri == "/frame") {
            val ts = session.parameters["ts"]?.firstOrNull()?.toLongOrNull() ?: 0L
            val file = SnapshotFrames(SnapshotBuffer.directoryFor(context)).fileFor(ts)
            if (file == null) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Frame not found")
            }
            val response = newFixedLengthResponse(
                Response.Status.OK, "image/jpeg", FileInputStream(file), file.length()
            )
            response.addHeader("Cache-Control", "private, max-age=3600")
            addFrameAgeHeaders(response, ts)
            return response
        }

        if (uri == "/api/health") {
            return jsonResponse(buildHealthJson())
        }

        if (uri == "/" || uri == "/index.html" || uri == "/greet.html") {
            return serveAsset("index.html", "text/html; charset=utf-8")
        }
        if (uri == "/app.css") {
            return serveAsset("app.css", "text/css; charset=utf-8")
        }
        if (uri == "/app.js") {
            return serveAsset("app.js", "application/javascript; charset=utf-8")
        }
        if (uri == "/favicon.svg") {
            return serveAsset("favicon.svg", "image/svg+xml")
        }
        
        // Legacy redirect
        if (uri.startsWith("/server/")) {
             if (uri.endsWith("on")) {
                 onStartStream()
             } else {
                 onStopStream()
             }
             val response = newFixedLengthResponse(Response.Status.REDIRECT, MIME_PLAINTEXT, "")
             response.addHeader("Location", "/")
             return response
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }

    private fun escapeJson(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun buildRtspUrl(): String {
        val authOn = getAuthEnabled()
        val user = getUsername()
        val pass = getPassword()
        return if (authOn && user.isNotEmpty() && pass.isNotEmpty()) {
            "rtsp://$user:$pass@$ipAddress:8554/stream"
        } else {
            "rtsp://$ipAddress:8554/stream"
        }
    }

    /**
     * Serves a bundled dashboard file from assets/web. The caller passes a
     * fixed file name, never the request path, so a request cannot select an arbitrary asset.
     */
    private fun serveAsset(assetName: String, mime: String): Response {
        val bytes = try {
            context.assets.open("web/" + assetName).use { it.readBytes() }
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
        val response = newFixedLengthResponse(
            Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong()
        )
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("X-Content-Type-Options", "nosniff")
        if (assetName == "index.html") {
            response.addHeader("Content-Security-Policy", WEB_CSP)
            response.addHeader("X-Frame-Options", "DENY")
            response.addHeader("Referrer-Policy", "no-referrer")
        }
        return response
    }

    /** Adds X-Frame-Timestamp and X-Frame-Age (ms); nothing is added when the capture time is unknown. */
    private fun addFrameAgeHeaders(response: Response, capturedMs: Long) {
        val now = System.currentTimeMillis()
        val age = SnapshotFrames.ageMs(now, capturedMs)
        if (age < 0L) return
        response.addHeader("X-Frame-Timestamp", capturedMs.toString())
        response.addHeader("X-Frame-Age", age.toString())
    }

    private fun jsonResponse(json: String): Response {
        val response = newFixedLengthResponse(Response.Status.OK, "application/json", json)
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    /** An int extra from the sticky battery broadcast, or -1; reading it needs no receiver. */
    private fun batteryExtra(name: String): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return intent?.getIntExtra(name, -1) ?: -1
    }

    private fun buildHealthJson(): String {
        val frames = SnapshotFrames(SnapshotBuffer.directoryFor(context))
        val stats = frames.stats()
        val dir = SnapshotBuffer.directoryFor(context)
        val coverage = SnapshotFrames.coveragePercent(
            stats.oldestMs, stats.newestMs, SnapshotBuffer.DEFAULT_RETENTION_MS
        )
        val plugged = batteryExtra(BatteryManager.EXTRA_PLUGGED) > 0
        return "{" +
            "\"now\":" + System.currentTimeMillis() + "," +
            "\"uptimeMs\":" + (System.currentTimeMillis() - startedAtMs) + "," +
            "\"buffer\":{" +
                "\"count\":" + stats.count + "," +
                "\"totalBytes\":" + stats.totalBytes + "," +
                "\"oldest\":" + stats.oldestMs + "," +
                "\"newest\":" + stats.newestMs + "," +
                "\"retentionMs\":" + SnapshotBuffer.DEFAULT_RETENTION_MS + "," +
                "\"maxFiles\":" + SnapshotBuffer.DEFAULT_MAX_FILES + "," +
                "\"coveragePercent\":" + coverage + "," +
                "\"freeBytes\":" + dir.usableSpace + "," +
                "\"minFreeBytes\":" + SnapshotBuffer.DEFAULT_MIN_FREE_BYTES +
            "}," +
            "\"ports\":{" +
                "\"dashboard\":" + PORT + "," +
                "\"rtsp\":8554," +
                "\"rtspActive\":" + isStreaming() +
            "}," +
            "\"device\":{" +
                "\"batteryLevel\":" + getBatteryLevel() + "," +
                "\"batteryTempTenthsC\":" + batteryExtra(BatteryManager.EXTRA_TEMPERATURE) + "," +
                "\"charging\":" + plugged + "," +
                "\"wifiStrength\":" + getWifiStrength() + "," +
                "\"thermalStatus\":" + getThermalStatus() +
            "}" +
            "}"
    }
}
