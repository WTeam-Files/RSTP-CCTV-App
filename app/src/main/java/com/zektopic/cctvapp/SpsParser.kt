package com.zektopic.cctvapp

/** Reads the picture size out of an H.264 sequence parameter set (NAL without start code). */
object SpsParser {

    private class Bits(private val data: ByteArray) {
        private var pos = 0
        fun bit(): Int {
            if (pos >= data.size * 8) throw IllegalStateException("SPS truncated")
            val v = (data[pos shr 3].toInt() shr (7 - (pos and 7))) and 1
            pos++
            return v
        }
        fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
        fun ue(): Int {
            var zeros = 0
            while (bit() == 0) { zeros++; if (zeros > 31) throw IllegalStateException("bad ue") }
            return if (zeros == 0) 0 else ((1 shl zeros) - 1) + bits(zeros)
        }
        fun se(): Int {
            val k = ue()
            return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
        }
    }

    /** Returns (width, height) or null when the SPS cannot be parsed. */
    fun dimensions(sps: ByteArray): Pair<Int, Int>? = try {
        parse(unescape(sps))
    } catch (e: Exception) {
        null
    }

    /** Removes emulation-prevention bytes (00 00 03 -> 00 00). */
    private fun unescape(nal: ByteArray): ByteArray {
        val out = ArrayList<Byte>(nal.size)
        var zeros = 0
        for (b in nal) {
            if (zeros >= 2 && b == 3.toByte()) { zeros = 0; continue }
            out.add(b)
            zeros = if (b == 0.toByte()) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private fun parse(rbsp: ByteArray): Pair<Int, Int> {
        val r = Bits(rbsp.copyOfRange(1, rbsp.size)) // skip the NAL header byte
        val profile = r.bits(8)
        r.bits(8); r.bits(8)
        r.ue()
        var chroma = 1
        if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = r.ue()
            if (chroma == 3) r.bit()
            r.ue(); r.ue(); r.bit()
            if (r.bit() == 1) {
                repeat(if (chroma != 3) 8 else 12) { i ->
                    if (r.bit() == 1) {
                        var last = 8; var next = 8
                        repeat(if (i < 6) 16 else 64) {
                            if (next != 0) { next = (last + r.se() + 256) % 256 }
                            last = if (next == 0) last else next
                        }
                    }
                }
            }
        }
        r.ue()
        when (r.ue()) {
            0 -> r.ue()
            1 -> { r.bit(); r.se(); r.se(); repeat(r.ue()) { r.se() } }
        }
        r.ue(); r.bit()
        val widthMbs = r.ue() + 1
        val heightMapUnits = r.ue() + 1
        val frameMbsOnly = r.bit()
        if (frameMbsOnly == 0) r.bit()
        r.bit()
        var cropL = 0; var cropR = 0; var cropT = 0; var cropB = 0
        if (r.bit() == 1) { cropL = r.ue(); cropR = r.ue(); cropT = r.ue(); cropB = r.ue() }
        val cropX = if (chroma == 0) 1 else 2
        val cropY = (if (chroma == 0) 1 else 2) * (2 - frameMbsOnly)
        val width = widthMbs * 16 - (cropL + cropR) * cropX
        val height = (2 - frameMbsOnly) * heightMapUnits * 16 - (cropT + cropB) * cropY
        return width to height
    }
}
