package com.zektopic.cctvapp

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Minimal fragmented-MP4 writer for a single H.264 video track, so a browser `<video>`
 * can play the camera's encoded stream over plain HTTP.
 *
 * One init segment (ftyp + moov, carrying the SPS/PPS) is followed by one moof+mdat
 * fragment per frame. No B-frames are assumed, which holds for the hardware baseline
 * encoder this app uses, so decode time equals presentation time.
 */
object Fmp4Muxer {

    const val TIMESCALE = 90_000

    /** A frame already split out of an Annex B buffer: length-prefixed NAL units. */
    class Sample(val avcc: ByteArray, val keyframe: Boolean)

    /** Strips a leading 00 00 01 / 00 00 00 01 start code, if there is one. */
    fun stripStartCode(nal: ByteArray): ByteArray {
        val skip = when {
            nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
                nal[2] == 0.toByte() && nal[3] == 1.toByte() -> 4
            nal.size >= 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
                nal[2] == 1.toByte() -> 3
            else -> 0
        }
        return if (skip == 0) nal else nal.copyOfRange(skip, nal.size)
    }

    /** Splits an Annex B buffer into NAL units without their start codes. */
    fun splitNals(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>() // index of the first byte after each start code
        val codeStarts = ArrayList<Int>()
        var i = 0
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    codeStarts.add(i); starts.add(i + 3); i += 3; continue
                }
                if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    codeStarts.add(i); starts.add(i + 4); i += 4; continue
                }
            }
            i++
        }
        val nals = ArrayList<ByteArray>()
        for (n in starts.indices) {
            val end = if (n + 1 < starts.size) codeStarts[n + 1] else data.size
            if (end > starts[n]) nals.add(data.copyOfRange(starts[n], end))
        }
        return nals
    }

    /**
     * Converts one encoder output buffer to length-prefixed samples, keeping only picture
     * NAL units (types 1 and 5). SPS/PPS are reported through [onParameterSet] instead.
     * Returns null when the buffer holds no picture data.
     */
    fun toSample(annexB: ByteArray, onParameterSet: (type: Int, nal: ByteArray) -> Unit): Sample? {
        val out = ByteArrayOutputStream(annexB.size + 16)
        var key = false
        var any = false
        for (nal in splitNals(annexB)) {
            val type = nal[0].toInt() and 0x1F
            when (type) {
                7, 8 -> onParameterSet(type, nal)
                1, 5 -> {
                    if (type == 5) key = true
                    any = true
                    out.write(nal.size ushr 24); out.write(nal.size ushr 16)
                    out.write(nal.size ushr 8); out.write(nal.size)
                    out.write(nal)
                }
            }
        }
        return if (any) Sample(out.toByteArray(), key) else null
    }

    fun initSegment(sps: ByteArray, pps: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(box("ftyp") {
            str("isom"); int(0x200); str("isom"); str("iso5"); str("avc1"); str("mp41")
        })
        out.write(box("moov") {
            raw(box("mvhd") {
                int(0); int(0); int(0); int(TIMESCALE); int(0)
                int(0x00010000); short(0x0100); short(0); int(0); int(0)
                matrix(); repeat(6) { int(0) }; int(2)
            })
            raw(box("trak") {
                raw(box("tkhd") {
                    int(0x00000007); int(0); int(0); int(1); int(0); int(0)
                    int(0); int(0); short(0); short(0); short(0); short(0)
                    matrix(); int(width shl 16); int(height shl 16)
                })
                raw(box("mdia") {
                    raw(box("mdhd") {
                        int(0); int(0); int(0); int(TIMESCALE); int(0); short(0x55C4); short(0)
                    })
                    raw(box("hdlr") {
                        int(0); int(0); str("vide"); int(0); int(0); int(0)
                        bytes("VideoHandler".toByteArray()); byte(0)
                    })
                    raw(box("minf") {
                        raw(box("vmhd") { int(1); short(0); short(0); short(0); short(0) })
                        raw(box("dinf") {
                            raw(box("dref") {
                                int(0); int(1)
                                raw(box("url ") { int(1) })
                            })
                        })
                        raw(box("stbl") {
                            raw(box("stsd") {
                                int(0); int(1)
                                raw(box("avc1") {
                                    repeat(6) { byte(0) }; short(1)
                                    short(0); short(0); int(0); int(0); int(0)
                                    short(width); short(height)
                                    int(0x00480000); int(0x00480000); int(0); short(1)
                                    repeat(32) { byte(0) }
                                    short(0x0018); short(-1)
                                    raw(box("avcC") {
                                        byte(1); byte(sps[1].toInt()); byte(sps[2].toInt())
                                        byte(sps[3].toInt()); byte(0xFF); byte(0xE1)
                                        short(sps.size); bytes(sps)
                                        byte(1); short(pps.size); bytes(pps)
                                    })
                                })
                            })
                            raw(box("stts") { int(0); int(0) })
                            raw(box("stsc") { int(0); int(0) })
                            raw(box("stsz") { int(0); int(0); int(0) })
                            raw(box("stco") { int(0); int(0) })
                        })
                    })
                })
            })
            raw(box("mvex") {
                raw(box("trex") { int(0); int(1); int(1); int(0); int(0); int(0) })
            })
        })
        return out.toByteArray()
    }

    /** One moof+mdat pair holding a single sample. */
    fun fragment(
        sequence: Int,
        decodeTime: Long,
        duration: Int,
        sample: ByteArray,
        keyframe: Boolean
    ): ByteArray {
        // data_offset points from the start of moof to the first byte of mdat payload, so
        // the moof size has to be known before the trun is written.
        fun build(dataOffset: Int): ByteArray = box("moof") {
            raw(box("mfhd") { int(0); int(sequence) })
            raw(box("traf") {
                raw(box("tfhd") { int(0x020000); int(1) })
                raw(box("tfdt") { int(0x01000000); long(decodeTime) })
                raw(box("trun") {
                    int(0x000701); int(1); int(dataOffset)
                    int(duration); int(sample.size)
                    int(if (keyframe) 0x02000000 else 0x01010000)
                })
            })
        }
        val size = build(0).size
        val moof = build(size + 8)
        val out = ByteArrayOutputStream(moof.size + sample.size + 8)
        out.write(moof)
        out.write(box("mdat") { bytes(sample) })
        return out.toByteArray()
    }

    // --- box plumbing -----------------------------------------------------------------

    private class Writer {
        val out = ByteArrayOutputStream()
        fun byte(v: Int) = out.write(v and 0xFF)
        fun short(v: Int) { out.write((v shr 8) and 0xFF); out.write(v and 0xFF) }
        fun int(v: Int) {
            out.write((v ushr 24) and 0xFF); out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF); out.write(v and 0xFF)
        }
        fun long(v: Long) { int((v ushr 32).toInt()); int(v.toInt()) }
        fun str(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        fun bytes(b: ByteArray) = out.write(b)
        fun raw(b: ByteArray) = out.write(b)
        fun matrix() {
            int(0x00010000); int(0); int(0); int(0); int(0x00010000); int(0)
            int(0); int(0); int(0x40000000)
        }
    }

    private fun box(type: String, body: Writer.() -> Unit): ByteArray {
        val w = Writer()
        w.body()
        val payload = w.out.toByteArray()
        return ByteBuffer.allocate(8 + payload.size)
            .putInt(8 + payload.size)
            .put(type.toByteArray(Charsets.ISO_8859_1))
            .put(payload)
            .array()
    }
}
