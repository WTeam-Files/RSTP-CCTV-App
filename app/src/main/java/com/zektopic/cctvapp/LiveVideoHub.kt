package com.zektopic.cctvapp

import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Fans the encoder's H.264 output out to browser clients as a live fragmented MP4.
 *
 * The camera pipeline calls [onParameterSets] and [onFrame] from the encoder thread; each
 * connected client gets its own bounded queue and an [InputStream] that the web server
 * streams as a chunked response. A client that cannot keep up is dropped rather than
 * allowed to hold up the encoder, and simply reconnects.
 */
class LiveVideoHub {

    companion object {
        private const val MAX_QUEUED_FRAMES = 150
        private const val WAIT_FOR_SPS_MS = 10_000L
        private const val WAIT_FOR_FRAME_MS = 15_000L
    }

    private class Frame(val sample: ByteArray, val keyframe: Boolean, val ptsUs: Long)

    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null
    @Volatile private var width = 0
    @Volatile private var height = 0
    private val clients = CopyOnWriteArrayList<ClientStream>()

    val clientCount: Int get() = clients.size

    /** Wall-clock time of the newest encoder frame, whether or not anyone is watching. */
    @Volatile var lastFrameWallMs: Long = 0L
        private set

    /** Encoded frames seen since process start; the service diffs it to measure encoder fps. */
    val frameCount = AtomicLong()

    /** Browser clients dropped for not keeping up; a sign the link is saturated. */
    val overflowCount = AtomicLong()

    /** SPS/PPS as raw NAL units; a changed SPS means a new init segment, so clients restart. */
    fun onParameterSets(newSps: ByteArray, newPps: ByteArray) {
        val s = Fmp4Muxer.stripStartCode(newSps)
        val p = Fmp4Muxer.stripStartCode(newPps)
        val changed = sps?.contentEquals(s) != true || pps?.contentEquals(p) != true
        val dims = SpsParser.dimensions(s)
        if (changed && sps != null) closeAllClients()
        if (dims != null) { width = dims.first; height = dims.second }
        sps = s
        pps = p
    }

    /** One encoder output buffer in Annex B form. Copies what it needs before returning. */
    fun onFrame(annexB: ByteArray, ptsUs: Long) {
        frameCount.incrementAndGet()
        lastFrameWallMs = System.currentTimeMillis()
        if (clients.isEmpty() && sps != null) return
        val sample = Fmp4Muxer.toSample(annexB) { type, nal ->
            // Some encoders only emit parameter sets in-band with the IDR frame.
            if (type == 7) pendingSps = nal else pendingPps = nal
        } ?: return
        val ps = pendingSps; val pp = pendingPps
        if (ps != null && pp != null && sps == null) onParameterSets(ps, pp)
        val frame = Frame(sample.avcc, sample.keyframe, ptsUs)
        for (c in clients) c.offer(frame)
    }

    @Volatile private var pendingSps: ByteArray? = null
    @Volatile private var pendingPps: ByteArray? = null

    fun open(): InputStream = ClientStream().also { clients.add(it) }

    fun closeAllClients() {
        for (c in clients) c.close()
    }

    private inner class ClientStream : InputStream() {
        private val queue = LinkedBlockingQueue<Frame>(MAX_QUEUED_FRAMES)
        @Volatile private var closed = false
        @Volatile private var waitingForKey = true
        private var current = ByteArray(0)
        private var pos = 0
        private var initSent = false
        private var pending: Frame? = null
        private var firstPtsUs = -1L
        private var sequence = 1

        fun offer(frame: Frame) {
            if (closed) return
            if (waitingForKey && !frame.keyframe) return
            waitingForKey = false
            if (!queue.offer(frame)) {
                overflowCount.incrementAndGet()
                close()
            }
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= current.size) {
                if (!fill()) return -1
            }
            val n = minOf(len, current.size - pos)
            System.arraycopy(current, pos, b, off, n)
            pos += n
            return n
        }

        /** Produces the next chunk of the MP4; false means the stream is over. */
        private fun fill(): Boolean {
            if (closed) return false
            if (!initSent) {
                val deadline = System.currentTimeMillis() + WAIT_FOR_SPS_MS
                while (sps == null || pps == null || width == 0) {
                    if (closed || System.currentTimeMillis() > deadline) return false
                    Thread.sleep(100)
                }
                current = Fmp4Muxer.initSegment(sps!!, pps!!, width, height)
                pos = 0
                initSent = true
                return true
            }
            while (true) {
                val next = queue.poll(WAIT_FOR_FRAME_MS, TimeUnit.MILLISECONDS) ?: return false
                if (closed) return false
                val prev = pending
                pending = next
                if (prev == null) continue // need the following frame to know this one's duration
                if (firstPtsUs < 0) firstPtsUs = prev.ptsUs
                val ticks = { us: Long -> (us - firstPtsUs) * Fmp4Muxer.TIMESCALE / 1_000_000L }
                val duration = (ticks(next.ptsUs) - ticks(prev.ptsUs)).coerceIn(1L, 90_000L).toInt()
                current = Fmp4Muxer.fragment(
                    sequence++, ticks(prev.ptsUs).coerceAtLeast(0L), duration, prev.sample, prev.keyframe
                )
                pos = 0
                return true
            }
        }

        override fun close() {
            closed = true
            clients.remove(this)
            queue.clear()
        }
    }
}
