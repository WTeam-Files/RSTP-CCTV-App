package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import com.zektopic.cctvapp.LinkTierPolicy.Pressure
import com.zektopic.cctvapp.LinkTierPolicy.Sample

class ResilienceTest {

    private val ok = Sample()
    private val severe = Sample(congested60 = true)
    private val moderate = Sample(congested20 = true)

    @Test
    fun `tiers run 4000-30 down to 500-10`() {
        val t = LinkTierPolicy.TIERS
        assertEquals(listOf(4000, 2500, 1500, 800, 500), t.map { it.kbps })
        assertEquals(listOf(30, 30, 20, 15, 10), t.map { it.fps })
    }

    @Test
    fun `pressure classification`() {
        assertEquals(Pressure.OK, LinkTierPolicy.pressureOf(ok))
        assertEquals(Pressure.MODERATE, LinkTierPolicy.pressureOf(moderate))
        assertEquals(Pressure.SEVERE, LinkTierPolicy.pressureOf(severe))
        assertEquals(Pressure.SEVERE, LinkTierPolicy.pressureOf(Sample(viewerOverflow = true)))
        assertEquals(Pressure.HOLD, LinkTierPolicy.pressureOf(Sample(rssiDbm = -82)))
        assertEquals(Pressure.SEVERE, LinkTierPolicy.pressureOf(Sample(rssiDbm = -90)))
        assertEquals(Pressure.HOLD, LinkTierPolicy.pressureOf(Sample(linkMbps = 6)))
        assertEquals(Pressure.OK, LinkTierPolicy.pressureOf(Sample(rssiDbm = -55, linkMbps = 72)))
        // Unknown readings (-1 / 0) must not look like a dead link.
        assertEquals(Pressure.OK, LinkTierPolicy.pressureOf(Sample(rssiDbm = -127 + 127, linkMbps = -1)))
    }

    @Test
    fun `two severe samples drop one tier then cooldown holds`() {
        val p = LinkTierPolicy()
        assertFalse(p.onSample(0, severe))
        assertTrue(p.onSample(2_000, severe))
        assertEquals(1, p.tier)
        // Still severe, but inside the cooldown.
        assertFalse(p.onSample(4_000, severe))
        assertFalse(p.onSample(6_000, severe))
        assertTrue(p.onSample(12_000, severe))
        assertEquals(2, p.tier)
    }

    @Test
    fun `moderate pressure needs four samples`() {
        val p = LinkTierPolicy()
        repeat(3) { assertFalse(p.onSample(it * 2_000L, moderate)) }
        assertTrue(p.onSample(6_000, moderate))
        assertEquals(1, p.tier)
    }

    @Test
    fun `never steps below the last tier`() {
        val p = LinkTierPolicy(cooldownMs = 0)
        var now = 0L
        repeat(40) { p.onSample(now, severe); now += 2_000 }
        assertEquals(LinkTierPolicy.TIERS.size - 1, p.tier)
    }

    @Test
    fun `ramps back slowly and slower right after a drop`() {
        val p = LinkTierPolicy()
        p.onSample(0, severe); p.onSample(2_000, severe)
        assertEquals(1, p.tier)
        var now = 4_000L
        var steps = 0
        while (p.tier == 1 && steps < 1000) { p.onSample(now, ok); now += 2_000; steps++ }
        // Just dropped: needs the longer 150-sample (5 minute) calm before climbing.
        assertTrue("climbed after $steps samples", steps >= 150)
        assertEquals(0, p.tier)
    }

    @Test
    fun `hold pressure resets the calm streak`() {
        val p = LinkTierPolicy(okSamplesToRaise = 5, okSamplesToRaiseAfterDrop = 5, cooldownMs = 0)
        p.onSample(0, severe); p.onSample(1, severe)
        assertEquals(1, p.tier)
        repeat(4) { p.onSample(10L + it, ok) }
        p.onSample(20, Sample(rssiDbm = -83))
        repeat(4) { p.onSample(30L + it, ok) }
        assertEquals(1, p.tier)
        p.onSample(40, ok)
        assertEquals(0, p.tier)
    }

    @Test
    fun `restart budget allows a few per window then refuses`() {
        val b = RestartBudget(maxRestarts = 3, windowMs = 1_000)
        repeat(3) { assertTrue(b.allow(it.toLong())); b.record(it.toLong()) }
        assertFalse(b.allow(500))
        assertTrue(b.allow(2_000))
    }

    @Test
    fun `log ring keeps only the newest lines`() {
        val r = LogRing(3)
        (1..5).forEach { r.add("l$it") }
        assertEquals(listOf("l3", "l4", "l5"), r.tail(10))
        assertEquals(listOf("l5"), r.tail(1))
    }

    @Test
    fun `etag matching handles lists weak validators and star`() {
        val e = HttpCache.etag("s", 123)
        assertEquals("\"s123\"", e)
        assertTrue(HttpCache.matches("\"s123\"", e))
        assertTrue(HttpCache.matches("W/\"s123\", \"other\"", e))
        assertTrue(HttpCache.matches("*", e))
        assertFalse(HttpCache.matches("\"s124\"", e))
        assertFalse(HttpCache.matches(null, e))
        assertEquals("\"f9-w160\"", HttpCache.etag("f", 9, 160))
    }

    @Test
    fun `gzip round trips and accept-encoding is parsed`() {
        val text = "{\"a\":1}".repeat(100).toByteArray()
        val z = HttpCache.gzip(text)
        assertTrue(z.size < text.size)
        assertEquals(String(text), String(GZIPInputStream(ByteArrayInputStream(z)).readBytes()))
        assertTrue(HttpCache.acceptsGzip("br, gzip;q=0.8"))
        assertFalse(HttpCache.acceptsGzip("identity"))
        assertFalse(HttpCache.acceptsGzip(null))
    }

    @Test
    fun `thumbnail width is clamped and optional`() {
        assertNull(HttpCache.thumbWidth(null))
        assertNull(HttpCache.thumbWidth("abc"))
        assertEquals(160, HttpCache.thumbWidth("160"))
        assertEquals(48, HttpCache.thumbWidth("5"))
        assertEquals(640, HttpCache.thumbWidth("5000"))
    }
}
