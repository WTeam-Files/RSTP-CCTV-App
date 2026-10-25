package com.zektopic.cctvapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

/** Small JPEG thumbnails of buffered frames, so the gallery grid does not pull full-size images. */
object Thumbnails {
    private const val QUALITY = 60
    private const val MAX_CACHED = 300

    private val cache = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > MAX_CACHED
    }

    @Synchronized private fun cached(key: String): ByteArray? = cache[key]
    @Synchronized private fun remember(key: String, bytes: ByteArray) { cache[key] = bytes }

    fun get(key: String, file: File, width: Int): ByteArray? {
        cached(key)?.let { return it }
        val made = make(file, width) ?: return null
        remember(key, made)
        return made
    }

    private fun make(file: File, width: Int): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = CaptureProfile.sampleSizeFor(bounds.outWidth, width)
            }
            val decoded = BitmapFactory.decodeFile(file.path, opts) ?: return null
            val scaled = if (decoded.width > width) {
                Bitmap.createScaledBitmap(decoded, width, decoded.height * width / decoded.width, true)
            } else decoded
            val out = ByteArrayOutputStream(8 * 1024)
            scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            null
        }
    }
}
