package com.zektopic.cctvapp

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rolling on-disk buffer of the most recent snapshots, laid out as
 * `<root>/YYYY-MM-DD/HH/MMSS.jpg`.
 *
 * Retention follows [EventStore]: an age limit plus a hard count cap, because age alone
 * does not bound disk use if the capture interval is lowered. Takes a plain directory so
 * it can be exercised by JVM unit tests; [forContext] is the production entry point.
 *
 * Nothing here throws to the caller -- a full disk or a vanished SD card must never take
 * the capture loop down, so every failure is reported through the return value instead.
 */
class SnapshotBuffer(
    private val rootDir: File,
    private val retentionMs: Long = DEFAULT_RETENTION_MS,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
    private val minFreeBytes: Long = DEFAULT_MIN_FREE_BYTES
) {
    companion object {
        const val DEFAULT_RETENTION_MS: Long = 3L * 60L * 60L * 1000L

        /** 3 h at one frame per 5 s is 2160 files; the cap leaves headroom for faster rates. */
        const val DEFAULT_MAX_FILES: Int = 4000

        const val DEFAULT_MIN_FREE_BYTES: Long = 200L * 1024L * 1024L

        /** Pruning scans the tree, so it runs at most this often. */
        private const val PRUNE_INTERVAL_MS = 60_000L

        /** Where the buffer lives; shared with the dashboard's read-only [SnapshotFrames]. */
        fun directoryFor(context: Context): File {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return File(base, "snapshot-buffer")
        }

        fun forContext(context: Context): SnapshotBuffer? {
            return SnapshotBuffer(directoryFor(context))
        }
    }

    private val lock = Any()
    private var lastPruneMs = 0L

    /** Writes [jpeg] atomically. Returns false when skipped or failed. */
    fun write(jpeg: ByteArray, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (jpeg.isEmpty()) return false
        return try {
            synchronized(lock) {
                if (!rootDir.exists()) rootDir.mkdirs()
                if (rootDir.usableSpace < minFreeBytes) return false

                val dir = File(
                    rootDir,
                    format("yyyy-MM-dd", nowMs) + File.separator + format("HH", nowMs)
                )
                if (!dir.exists() && !dir.mkdirs() && !dir.exists()) return false

                val name = format("mmss", nowMs)
                val target = File(dir, "$name.jpg")
                val tmp = File(dir, "$name.jpg.tmp")
                tmp.writeBytes(jpeg)
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    return false
                }

                if (nowMs - lastPruneMs >= PRUNE_INTERVAL_MS) {
                    lastPruneMs = nowMs
                    prune(nowMs)
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Deletes files older than the retention window, then the oldest files beyond the cap,
     * then any directories left empty. Age comes from lastModified, which [write] never
     * touches after the rename, so a backdated file is pruned the same as a genuinely old one.
     */
    fun prune(nowMs: Long = System.currentTimeMillis()): Int {
        var removed = 0
        try {
            synchronized(lock) {
                val files = rootDir.walkTopDown().filter { it.isFile }.toMutableList()
                val cutoff = nowMs - retentionMs
                val survivors = ArrayList<File>(files.size)
                for (f in files) {
                    // Orphaned .tmp files from an interrupted write go with the old ones.
                    if (f.lastModified() < cutoff) {
                        if (f.delete()) removed++
                    } else {
                        survivors.add(f)
                    }
                }
                if (survivors.size > maxFiles) {
                    survivors.sortBy { it.lastModified() }
                    for (f in survivors.take(survivors.size - maxFiles)) {
                        if (f.delete()) removed++
                    }
                }
                rootDir.walkBottomUp()
                    .filter { it.isDirectory && it != rootDir && it.list().isNullOrEmpty() }
                    .forEach { it.delete() }
            }
        } catch (e: Exception) {
            // Best effort; the next pass tries again.
        }
        return removed
    }

    fun fileCount(): Int = rootDir.walkTopDown().count { it.isFile && it.name.endsWith(".jpg") }

    private fun format(pattern: String, ms: Long): String =
        SimpleDateFormat(pattern, Locale.US).format(Date(ms))
}
