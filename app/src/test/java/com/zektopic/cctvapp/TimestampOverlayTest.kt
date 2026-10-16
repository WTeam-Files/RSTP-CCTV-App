package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimestampOverlayTest {

    private val positions = listOf("Top Left", "Top Right", "Bottom Left", "Bottom Right")
    private val sizes = listOf("Small", "Medium", "Large")

    @Test
    fun `medium keeps its historical scale and insets`() {
        val geometry = TimestampOverlay.geometryFor("Top Left", "Medium")
        assertEquals(35f, geometry.scaleX, 0.001f)
        assertEquals(10f, geometry.scaleY, 0.001f)
        assertEquals(2f, geometry.posX, 0.001f)
        assertEquals(2f, geometry.posY, 0.001f)
    }

    @Test
    fun `every corner stays fully on screen for every size`() {
        for (size in sizes) {
            for (position in positions) {
                val g = TimestampOverlay.geometryFor(position, size)
                assertTrue("$position/$size posX=${g.posX}", g.posX in 0f..100f)
                assertTrue("$position/$size posY=${g.posY}", g.posY in 0f..100f)
                assertTrue(
                    "$position/$size right edge=${g.posX + g.scaleX} overflows",
                    g.posX + g.scaleX <= 100f + 0.001f
                )
                assertTrue(
                    "$position/$size bottom edge=${g.posY + g.scaleY} overflows",
                    g.posY + g.scaleY <= 100f + 0.001f
                )
            }
        }
    }

    @Test
    fun `right and bottom edges sit one margin inside the stream edge`() {
        for (size in sizes) {
            val (scaleX, scaleY) = TimestampOverlay.scaleForSize(size)
            val right = TimestampOverlay.geometryFor("Top Right", size)
            assertEquals(100f - TimestampOverlay.MARGIN_PERCENT, right.posX + scaleX, 0.001f)
            val bottom = TimestampOverlay.geometryFor("Bottom Left", size)
            assertEquals(100f - TimestampOverlay.MARGIN_PERCENT, bottom.posY + scaleY, 0.001f)
        }
    }

    @Test
    fun `large bottom-right fits on screen`() {
        val g = TimestampOverlay.geometryFor("Bottom Right", "Large")
        assertEquals(100f - 45f - 2f, g.posX, 0.001f)
        assertEquals(100f - 14f - 2f, g.posY, 0.001f)
    }

    @Test
    fun `unknown size and position fall back to medium top-left rather than throwing`() {
        val g = TimestampOverlay.geometryFor("Nowhere", "Huge")
        assertEquals(35f, g.scaleX, 0.001f)
        assertEquals(10f, g.scaleY, 0.001f)
        assertEquals(2f, g.posX, 0.001f)
        assertEquals(2f, g.posY, 0.001f)
    }

    @Test
    fun `portrait keeps the same pixel size as landscape`() {
        val landscape = TimestampOverlay.geometryFor("Top Left", "Medium", 1280, 720)
        val portrait = TimestampOverlay.geometryFor("Top Left", "Medium", 720, 1280)
        assertEquals(landscape.scaleX * 1280, portrait.scaleX * 720, 1f)
        assertEquals(landscape.scaleY * 720, portrait.scaleY * 1280, 1f)
    }

    @Test
    fun `portrait corners stay fully on screen for every size`() {
        for (size in sizes) {
            for (position in positions) {
                val g = TimestampOverlay.geometryFor(position, size, 720, 1280)
                assertTrue("$position/$size right edge=${g.posX + g.scaleX} overflows",
                    g.posX + g.scaleX <= 100f + 0.001f)
                assertTrue("$position/$size bottom edge=${g.posY + g.scaleY} overflows",
                    g.posY + g.scaleY <= 100f + 0.001f)
            }
        }
    }

    @Test
    fun `rotation 90 swaps the effective resolution`() {
        assertEquals(Pair(720, 1280), TimestampOverlay.effectiveResolution(1280, 720, 90))
        assertEquals(Pair(720, 1280), TimestampOverlay.effectiveResolution(1280, 720, 270))
        assertEquals(Pair(1280, 720), TimestampOverlay.effectiveResolution(1280, 720, 0))
        assertEquals(Pair(1280, 720), TimestampOverlay.effectiveResolution(1280, 720, 180))
    }
}
