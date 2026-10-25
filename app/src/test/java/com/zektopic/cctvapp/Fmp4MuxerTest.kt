package com.zektopic.cctvapp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class Fmp4MuxerTest {

    private class BitWriter {
        private val bits = ArrayList<Int>()
        fun u(n: Int, v: Int) { for (i in n - 1 downTo 0) bits.add((v shr i) and 1) }
        fun ue(v: Int) {
            val x = v + 1
            val len = 32 - Integer.numberOfLeadingZeros(x)
            u(len - 1, 0); u(len, x)
        }
        fun toBytes(): ByteArray {
            bits.add(1); while (bits.size % 8 != 0) bits.add(0)
            return ByteArray(bits.size / 8) { i ->
                var b = 0; for (k in 0 until 8) b = (b shl 1) or bits[i * 8 + k]; b.toByte()
            }
        }
    }

    /** Baseline SPS: 66, level 31, [widthMbs] x [heightMbs] macroblocks, no cropping. */
    private fun sps(widthMbs: Int, heightMbs: Int, cropBottomUnits: Int = 0): ByteArray {
        val w = BitWriter()
        w.u(8, 66); w.u(8, 0xC0); w.u(8, 31)
        w.ue(0); w.ue(0); w.ue(0); w.ue(0); w.ue(1); w.u(1, 0)
        w.ue(widthMbs - 1); w.ue(heightMbs - 1); w.u(1, 1); w.u(1, 1)
        if (cropBottomUnits > 0) { w.u(1, 1); w.ue(0); w.ue(0); w.ue(0); w.ue(cropBottomUnits) }
        else w.u(1, 0)
        w.u(1, 0)
        return byteArrayOf(0x67) + w.toBytes()
    }

    private fun boxes(data: ByteArray): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        var p = 0
        while (p + 8 <= data.size) {
            val size = ByteBuffer.wrap(data, p, 4).int
            val type = String(data, p + 4, 4, Charsets.ISO_8859_1)
            out.add(type to data.copyOfRange(p + 8, p + size))
            p += size
        }
        assertEquals("boxes must tile the buffer exactly", data.size, p)
        return out
    }

    @Test
    fun `sps dimensions are read for 720p and 480p`() {
        assertEquals(1280 to 720, SpsParser.dimensions(sps(80, 45)))
        assertEquals(640 to 480, SpsParser.dimensions(sps(40, 30)))
    }

    @Test
    fun `sps cropping is applied`() {
        // 1920x1088 coded, 4 crop units of 2 rows at the bottom -> 1080.
        assertEquals(1920 to 1080, SpsParser.dimensions(sps(120, 68, cropBottomUnits = 4)))
    }

    @Test
    fun `garbage sps returns null instead of throwing`() {
        assertNull(SpsParser.dimensions(byteArrayOf(0x67, 0x42)))
    }

    @Test
    fun `annex b is converted to length prefixed and keyframes are detected`() {
        val idr = byteArrayOf(0x65, 1, 2, 3)
        val p = byteArrayOf(0x41, 9, 9)
        val seen = ArrayList<Int>()
        val data = byteArrayOf(0, 0, 0, 1) + byteArrayOf(0x67, 5, 5) +
            byteArrayOf(0, 0, 0, 1) + byteArrayOf(0x68, 6) +
            byteArrayOf(0, 0, 1) + idr
        val s = Fmp4Muxer.toSample(data) { t, _ -> seen.add(t) }
        assertNotNull(s)
        assertTrue(s!!.keyframe)
        assertEquals(listOf(7, 8), seen)
        assertArrayEquals(byteArrayOf(0, 0, 0, 4) + idr, s.avcc)

        val s2 = Fmp4Muxer.toSample(byteArrayOf(0, 0, 0, 1) + p) { _, _ -> }
        assertTrue(!s2!!.keyframe)
        assertNull(Fmp4Muxer.toSample(byteArrayOf(0, 0, 0, 1, 0x09, 0x10)) { _, _ -> })
    }

    @Test
    fun `init segment is ftyp then moov with avcC`() {
        val init = Fmp4Muxer.initSegment(sps(80, 45), byteArrayOf(0x68, 1, 2), 1280, 720)
        val top = boxes(init)
        assertEquals(listOf("ftyp", "moov"), top.map { it.first })
        assertTrue(String(init, Charsets.ISO_8859_1).contains("avcC"))
        assertTrue(String(init, Charsets.ISO_8859_1).contains("mvex"))
    }

    @Test
    fun `fragment is moof then mdat and trun data offset lands on the payload`() {
        val sample = ByteArray(100) { 7 }
        val frag = Fmp4Muxer.fragment(3, 90_000L, 3000, sample, true)
        val top = boxes(frag)
        assertEquals(listOf("moof", "mdat"), top.map { it.first })
        val moofSize = 8 + top[0].second.size
        // The trun's data_offset field is the 3rd int of its body (flags, count, offset).
        val moof = frag.copyOfRange(0, moofSize)
        val idx = String(moof, Charsets.ISO_8859_1).indexOf("trun")
        val offset = ByteBuffer.wrap(moof, idx + 4 + 8, 4).int
        assertEquals(moofSize + 8, offset)
        assertArrayEquals(sample, top[1].second)
    }
}
