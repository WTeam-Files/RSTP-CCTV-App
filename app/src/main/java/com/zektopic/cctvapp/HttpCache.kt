package com.zektopic.cctvapp

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** Small helpers for conditional requests and compression, kept pure for unit tests. */
object HttpCache {

    /** Quoted strong ETag for a snapshot captured at [capturedMs], optionally a thumbnail of width [w]. */
    fun etag(prefix: String, capturedMs: Long, w: Int = 0): String =
        "\"" + prefix + capturedMs + (if (w > 0) "-w$w" else "") + "\""

    /** True when an If-None-Match header lists [etag] (or `*`). Weak validators match too. */
    fun matches(ifNoneMatch: String?, etag: String): Boolean {
        if (ifNoneMatch.isNullOrBlank()) return false
        if (ifNoneMatch.trim() == "*") return true
        return ifNoneMatch.split(',').map { it.trim().removePrefix("W/") }.any { it == etag }
    }

    fun acceptsGzip(acceptEncoding: String?): Boolean =
        acceptEncoding?.split(',')?.any { it.trim().substringBefore(';').equals("gzip", true) } == true

    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 2 + 32)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /** Parses `w=` for a thumbnail request; null means full size. Clamped to a sane range. */
    fun thumbWidth(raw: String?): Int? {
        val w = raw?.toIntOrNull() ?: return null
        return w.coerceIn(48, 640)
    }
}
