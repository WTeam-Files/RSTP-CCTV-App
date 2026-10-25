package com.zektopic.cctvapp

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Bounded in-memory line buffer; the newest [maxLines] lines are kept. Pure so it is unit tested. */
class LogRing(private val maxLines: Int) {
    private val lines = ArrayDeque<String>()

    @Synchronized fun add(line: String) {
        lines.addLast(line)
        while (lines.size > maxLines) lines.removeFirst()
    }

    /** The last [n] lines, oldest first. */
    @Synchronized fun tail(n: Int): List<String> {
        val all = lines.toList()
        return if (n >= all.size) all else all.subList(all.size - n.coerceAtLeast(0), all.size)
    }

    @Synchronized fun size(): Int = lines.size
}

/**
 * App-wide event log for remote diagnosis (served at /api/logs).
 *
 * Lines go to logcat, to a bounded ring and to a small file in device-protected storage so
 * the story of a crash survives the process restart that follows it. Only deliberate
 * events are recorded here -- lifecycle, failures, link tier changes, restarts.
 */
object AppLog {
    private const val MAX_LINES = 1500
    private const val MAX_FILE_BYTES = 256 * 1024L

    private val ring = LogRing(MAX_LINES)
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var file: File? = null
    @Volatile private var lastError: String = ""

    fun init(context: Context) {
        if (file != null) return
        try {
            val f = File(context.createDeviceProtectedStorageContext().filesDir, "applog.txt")
            file = f
            if (f.exists()) {
                // Seed the ring with the tail of the previous run.
                f.readLines().takeLast(400).forEach { ring.add(it) }
                ring.add(stamp() + " ---- process start ----")
            }
        } catch (e: Exception) {
            // Logging must never take the app down.
        }
    }

    fun i(tag: String, msg: String) = add("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = add("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) {
        lastError = stamp() + " " + tag + ": " + msg
        add("E", tag, msg, t)
    }

    fun lastError(): String = lastError

    fun tail(n: Int): List<String> = ring.tail(n)

    private fun add(level: String, tag: String, msg: String, t: Throwable?) {
        try {
            when (level) {
                "E" -> android.util.Log.e(tag, msg, t)
                "W" -> android.util.Log.w(tag, msg, t)
                else -> android.util.Log.i(tag, msg)
            }
        } catch (e: Throwable) {
            // android.util.Log is not available in plain JVM tests.
        }
        val line = stamp() + " " + level + "/" + tag + ": " + msg +
            (if (t != null) " (" + t.javaClass.simpleName + ": " + t.message + ")" else "")
        ring.add(line)
        val f = file ?: return
        try {
            writer.execute {
                try {
                    if (f.length() > MAX_FILE_BYTES) {
                        val keep = f.readLines().takeLast(300)
                        f.writeText(keep.joinToString("\n", postfix = "\n"))
                    }
                    f.appendText(line + "\n")
                } catch (e: Exception) {
                    // Disk full or storage gone; the ring still has it.
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun stamp(): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
}
