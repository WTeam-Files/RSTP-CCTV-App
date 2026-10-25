package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SnapshotBufferTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val jpeg = byteArrayOf(1, 2, 3)

    private fun buffer(maxFiles: Int = 100, minFree: Long = 0L) =
        SnapshotBuffer(File(tmp.root, "buf"), 3L * 3600_000L, maxFiles, minFree)

    @Test
    fun `write lays files out by date and hour and leaves no tmp file`() {
        val b = buffer()
        assertTrue(b.write(jpeg, 1_700_000_000_000L))
        val all = File(tmp.root, "buf").walkTopDown().filter { it.isFile }.toList()
        assertEquals(1, all.size)
        assertTrue(all[0].name.endsWith(".jpg"))
        assertEquals(3, all[0].length())
        assertEquals(3, all[0].relativeTo(File(tmp.root, "buf")).path.split(File.separator).size)
    }

    @Test
    fun `prune removes files older than retention and keeps recent ones`() {
        val b = buffer()
        val now = System.currentTimeMillis()
        b.write(jpeg, now)
        val old = File(tmp.root, "buf/old/00/0000.jpg").apply {
            parentFile.mkdirs(); writeBytes(jpeg); setLastModified(now - 4L * 3600_000L)
        }
        assertEquals(1, b.prune(now))
        assertFalse(old.exists())
        assertEquals(1, b.fileCount())
    }

    @Test
    fun `prune enforces the count cap by dropping the oldest`() {
        val b = buffer(maxFiles = 2)
        val now = System.currentTimeMillis()
        for (i in 0 until 4) {
            File(tmp.root, "buf/d/0$i/0000.jpg").apply {
                parentFile.mkdirs(); writeBytes(jpeg); setLastModified(now - (10 - i) * 1000L)
            }
        }
        assertEquals(2, b.prune(now))
        assertEquals(2, b.fileCount())
        assertFalse(File(tmp.root, "buf/d/00/0000.jpg").exists())
        assertTrue(File(tmp.root, "buf/d/03/0000.jpg").exists())
    }

    @Test
    fun `write is skipped when free space is below the floor`() {
        val b = buffer(minFree = Long.MAX_VALUE)
        assertFalse(b.write(jpeg))
        assertEquals(0, b.fileCount())
    }

    @Test
    fun `empty payload is ignored`() {
        assertFalse(buffer().write(ByteArray(0)))
    }
}
