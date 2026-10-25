package com.zektopic.cctvapp

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Read-only view of the on-disk [SnapshotBuffer] for the web dashboard.
 *
 * The buffer lays files out as `<root>/YYYY-MM-DD/HH/MMSS.jpg` in the device's local time
 * zone, so a frame's timestamp is recovered from its path (second resolution). Lookups by
 * timestamp rebuild the path from a number rather than accepting a path from the caller,
 * which makes directory traversal impossible by construction.
 *
 * Nothing here throws; an unreadable or missing directory simply looks empty.
 */
class SnapshotFrames(
    private val rootDir: File,
    private val timeZone: TimeZone = TimeZone.getDefault()
) {
    class Frame(val ts: Long, val sizeBytes: Long)

    class Stats(
        val count: Int,
        val totalBytes: Long,
        val oldestMs: Long,
        val newestMs: Long
    )

    companion object {
        const val DEFAULT_LIMIT = 120
        const val MAX_LIMIT = 5000

        fun clampLimit(raw: Int?): Int {
            if (raw == null) return DEFAULT_LIMIT
            return raw.coerceIn(1, MAX_LIMIT)
        }

        /** Builds the epoch-ms time for a `date`/`hour`/`name` path triple, or null if malformed. */
        fun parseTimestamp(date: String, hour: String, fileName: String, timeZone: TimeZone): Long? {
            if (!fileName.endsWith(".jpg")) return null
            val minSec = fileName.removeSuffix(".jpg")
            if (date.length != 10 || hour.length != 2 || minSec.length != 4) return null
            if (!(date + hour + minSec).all { it.isDigit() || it == '-' }) return null
            return try {
                val format = SimpleDateFormat("yyyy-MM-dd HH mmss", Locale.US)
                format.timeZone = timeZone
                format.isLenient = false
                format.parse("$date $hour $minSec")?.time
            } catch (e: Exception) {
                null
            }
        }

        /** Relative path (forward slashes) a frame written at [ts] lives at. */
        fun relativePath(ts: Long, timeZone: TimeZone): String {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val hour = SimpleDateFormat("HH", Locale.US)
            val minSec = SimpleDateFormat("mmss", Locale.US)
            date.timeZone = timeZone
            hour.timeZone = timeZone
            minSec.timeZone = timeZone
            val d = Date(ts)
            return date.format(d) + "/" + hour.format(d) + "/" + minSec.format(d) + ".jpg"
        }

        fun framesJson(frames: List<Frame>, stats: Stats): String {
            val sb = StringBuilder()
            sb.append("{\"count\":").append(stats.count)
            sb.append(",\"oldest\":").append(stats.oldestMs)
            sb.append(",\"newest\":").append(stats.newestMs)
            sb.append(",\"frames\":[")
            for ((index, frame) in frames.withIndex()) {
                if (index > 0) sb.append(',')
                sb.append("{\"ts\":").append(frame.ts)
                sb.append(",\"size\":").append(frame.sizeBytes).append('}')
            }
            sb.append("]}")
            return sb.toString()
        }

        /**
         * Milliseconds between [capturedMs] and [nowMs], or -1 when the capture time is
         * unknown (zero or negative). A capture time ahead of the clock -- skew or a clock
         * step -- reports 0 rather than a negative age.
         */
        fun ageMs(nowMs: Long, capturedMs: Long): Long {
            if (capturedMs <= 0L) return -1L
            val age = nowMs - capturedMs
            return if (age < 0L) 0L else age
        }

        /** Share of the retention window the buffer currently covers, 0..100. */
        fun coveragePercent(oldestMs: Long, newestMs: Long, retentionMs: Long): Int {
            if (retentionMs <= 0L || newestMs <= 0L || oldestMs <= 0L || newestMs < oldestMs) return 0
            val pct = (newestMs - oldestMs) * 100L / retentionMs
            return pct.coerceIn(0L, 100L).toInt()
        }
    }

    /** Newest-first frames, at most [limit], optionally only those strictly before [beforeMs]. */
    fun list(limit: Int, beforeMs: Long? = null): List<Frame> {
        val out = ArrayList<Frame>()
        try {
            for (dateDir in sortedDescending(rootDir)) {
                for (hourDir in sortedDescending(dateDir)) {
                    for (file in sortedDescending(hourDir)) {
                        if (out.size >= limit) return out
                        val ts = parseTimestamp(dateDir.name, hourDir.name, file.name, timeZone) ?: continue
                        if (beforeMs != null && ts >= beforeMs) continue
                        out.add(Frame(ts, file.length()))
                    }
                }
            }
        } catch (e: Exception) {
            // Directory vanished mid-scan; return what was found.
        }
        return out
    }

    fun stats(): Stats {
        var count = 0
        var total = 0L
        var oldest = 0L
        var newest = 0L
        try {
            for (dateDir in sortedDescending(rootDir)) {
                for (hourDir in sortedDescending(dateDir)) {
                    for (file in sortedDescending(hourDir)) {
                        val ts = parseTimestamp(dateDir.name, hourDir.name, file.name, timeZone) ?: continue
                        count++
                        total += file.length()
                        if (newest == 0L || ts > newest) newest = ts
                        if (oldest == 0L || ts < oldest) oldest = ts
                    }
                }
            }
        } catch (e: Exception) {
            // Best effort.
        }
        return Stats(count, total, oldest, newest)
    }

    /** The stored frame for exactly [ts], or null. */
    fun fileFor(ts: Long): File? {
        if (ts <= 0L) return null
        val file = File(rootDir, relativePath(ts, timeZone))
        return if (file.isFile) file else null
    }

    private fun sortedDescending(dir: File): List<File> {
        val children = dir.listFiles() ?: return emptyList()
        return children.sortedByDescending { it.name }
    }
}
