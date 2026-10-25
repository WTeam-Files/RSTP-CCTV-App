package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone

class SnapshotFramesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val utc = TimeZone.getTimeZone("UTC")
    private val root get() = File(tmp.root, "buf")

    // 2023-11-14 22:13:20 UTC
    private val base = 1_700_000_000_000L

    private fun frames() = SnapshotFrames(root, utc)

    private fun writeFrame(ts: Long, bytes: Int = 3) {
        val file = File(root, SnapshotFrames.relativePath(ts, utc))
        file.parentFile.mkdirs()
        file.writeBytes(ByteArray(bytes))
    }

    @Test
    fun `relativePath matches the layout SnapshotBuffer writes`() {
        assertEquals("2023-11-14/22/1320.jpg", SnapshotFrames.relativePath(base, utc))
    }

    @Test
    fun `parseTimestamp round trips relativePath`() {
        val parts = SnapshotFrames.relativePath(base, utc).split("/")
        assertEquals(base, SnapshotFrames.parseTimestamp(parts[0], parts[1], parts[2], utc))
    }

    @Test
    fun `parseTimestamp honours the time zone`() {
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        val inTokyo = SnapshotFrames.parseTimestamp("2023-11-14", "22", "1320.jpg", tokyo)
        assertEquals(base - 9L * 3600_000L, inTokyo)
    }

    @Test
    fun `parseTimestamp rejects malformed names`() {
        assertNull(SnapshotFrames.parseTimestamp("2023-11-14", "22", "1320.jpg.tmp", utc))
        assertNull(SnapshotFrames.parseTimestamp("2023-11-14", "22", "13.jpg", utc))
        assertNull(SnapshotFrames.parseTimestamp("2023-11-14", "2", "1320.jpg", utc))
        assertNull(SnapshotFrames.parseTimestamp("2023-11-14", "22", "abcd.jpg", utc))
        assertNull(SnapshotFrames.parseTimestamp("not-a-date", "22", "1320.jpg", utc))
        assertNull(SnapshotFrames.parseTimestamp("2023-13-45", "22", "1320.jpg", utc))
        assertNull(SnapshotFrames.parseTimestamp("2023-11-14", "22", "9999.jpg", utc))
        assertNull(SnapshotFrames.parseTimestamp("../../etc", "22", "1320.jpg", utc))
    }

    @Test
    fun `list is newest first across hour and day boundaries`() {
        val stamps = listOf(
            base,
            base + 5_000L,
            base + 3_600_000L,          // next hour
            base + 2L * 86_400_000L     // two days later
        )
        stamps.forEach { writeFrame(it) }
        val listed = frames().list(10).map { it.ts }
        assertEquals(stamps.sortedDescending(), listed)
    }

    @Test
    fun `list honours limit and before`() {
        for (i in 0 until 10) writeFrame(base + i * 5_000L)
        assertEquals(3, frames().list(3).size)
        assertEquals(base + 9 * 5_000L, frames().list(3)[0].ts)
        val before = frames().list(100, base + 5 * 5_000L).map { it.ts }
        assertEquals(5, before.size)
        assertEquals(base + 4 * 5_000L, before[0])
    }

    @Test
    fun `list reports file sizes and skips tmp and junk files`() {
        writeFrame(base, 42)
        File(root, "2023-11-14/22/1325.jpg.tmp").writeBytes(ByteArray(9))
        File(root, "2023-11-14/22/readme.txt").writeBytes(ByteArray(9))
        File(root, "stray.jpg").writeBytes(ByteArray(9))
        val listed = frames().list(10)
        assertEquals(1, listed.size)
        assertEquals(42L, listed[0].sizeBytes)
    }

    @Test
    fun `missing directory looks empty`() {
        assertTrue(frames().list(10).isEmpty())
        val stats = frames().stats()
        assertEquals(0, stats.count)
        assertEquals(0L, stats.totalBytes)
    }

    @Test
    fun `stats sums count bytes and bounds`() {
        writeFrame(base, 10)
        writeFrame(base + 60_000L, 20)
        writeFrame(base + 3_600_000L, 30)
        val s = frames().stats()
        assertEquals(3, s.count)
        assertEquals(60L, s.totalBytes)
        assertEquals(base, s.oldestMs)
        assertEquals(base + 3_600_000L, s.newestMs)
    }

    @Test
    fun `fileFor finds only exact stored frames`() {
        writeFrame(base)
        assertNotNull(frames().fileFor(base))
        assertNull(frames().fileFor(base + 1_000L))
        assertNull(frames().fileFor(0L))
        assertNull(frames().fileFor(-5L))
    }

    @Test
    fun `clampLimit applies default and bounds`() {
        assertEquals(SnapshotFrames.DEFAULT_LIMIT, SnapshotFrames.clampLimit(null))
        assertEquals(1, SnapshotFrames.clampLimit(0))
        assertEquals(1, SnapshotFrames.clampLimit(-7))
        assertEquals(50, SnapshotFrames.clampLimit(50))
        assertEquals(SnapshotFrames.MAX_LIMIT, SnapshotFrames.clampLimit(Int.MAX_VALUE))
    }

    @Test
    fun `coveragePercent is bounded and tolerates an empty buffer`() {
        val three = 3L * 3600_000L
        assertEquals(0, SnapshotFrames.coveragePercent(0L, 0L, three))
        assertEquals(50, SnapshotFrames.coveragePercent(base, base + three / 2, three))
        assertEquals(100, SnapshotFrames.coveragePercent(base, base + 2 * three, three))
        assertEquals(0, SnapshotFrames.coveragePercent(base + 10, base, three))
        assertEquals(0, SnapshotFrames.coveragePercent(base, base + 10, 0L))
    }

    @Test
    fun `framesJson is well formed`() {
        val json = SnapshotFrames.framesJson(
            listOf(SnapshotFrames.Frame(2000L, 5L), SnapshotFrames.Frame(1000L, 6L)),
            SnapshotFrames.Stats(2, 11L, 1000L, 2000L)
        )
        assertEquals(
            "{\"count\":2,\"oldest\":1000,\"newest\":2000,\"frames\":[{\"ts\":2000,\"size\":5},{\"ts\":1000,\"size\":6}]}",
            json
        )
        assertEquals(
            "{\"count\":0,\"oldest\":0,\"newest\":0,\"frames\":[]}",
            SnapshotFrames.framesJson(emptyList(), SnapshotFrames.Stats(0, 0L, 0L, 0L))
        )
    }

    @Test
    fun `frames written by SnapshotBuffer are listed back`() {
        val buffer = SnapshotBuffer(root, 3L * 3600_000L, 100, 0L)
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(utc)
            assertTrue(buffer.write(byteArrayOf(1, 2, 3), base))
            assertTrue(buffer.write(byteArrayOf(1, 2, 3), base + 5_000L))
            val listed = SnapshotFrames(root, utc).list(10).map { it.ts }
            assertEquals(listOf(base + 5_000L, base), listed)
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
