package org.pirsbd.tsdb.storage

import org.pirsbd.tsdb.TestLabels.metric
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.head.SnapshotSeries
import org.pirsbd.tsdb.storage.codec.CorruptBlockException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FileStorageTest {

    private fun tempDir(): Path = Files.createTempDirectory("tsdb-storage")

    private fun snapshotAt(from: Long, to: Long) = FixedSnapshotFor(
        listOf(SnapshotSeries(metric("cpu"), listOf(Sample(from, 1.0), Sample(to, 2.0)))),
    )

    @Test
    fun `an empty directory yields no blocks`() {
        FileStorage(tempDir()).use { assertEquals(emptyList(), it.blocks()) }
    }

    @Test
    fun `a directory that does not exist yet is created`() {
        val dir = tempDir().resolve("nested/data")

        FileStorage(dir).use { assertEquals(emptyList(), it.blocks()) }

        assertEquals(true, dir.exists())
    }

    @Test
    fun `a written block is visible after reopening the storage`() {
        val dir = tempDir()
        FileStorage(dir).use { it.write(snapshotAt(10, 20)) }

        FileStorage(dir).use { assertEquals(1, it.blocks().size) }
    }

    @Test
    fun `a leftover tmp folder is removed on open`() {
        val dir = tempDir()
        val leftover = dir.resolve("01ABC.tmp").also { it.createDirectories() }
        leftover.resolve("index").writeText("garbage")

        FileStorage(dir).use { assertEquals(emptyList(), it.blocks()) }

        assertFalse(leftover.exists())
    }

    @Test
    fun `blocks come out ordered by time and overlapping filters by range`() {
        val dir = tempDir()
        FileStorage(dir).use { storage ->
            storage.write(snapshotAt(100, 200))
            storage.write(snapshotAt(300, 400))

            assertEquals(2, storage.blocks().size)
            assertEquals(listOf(100L, 300L), storage.blocks().map { it.meta.range.minTime })
            assertEquals(
                listOf(300L),
                storage.overlapping(TimeRange(350, 360)).map { it.meta.range.minTime },
            )
            assertEquals(emptyList(), storage.overlapping(TimeRange(250, 260)).map { it.meta.range.minTime })
        }
    }

    @Test
    fun `a block folder with a corrupt index fails loudly on open`() {
        val dir = tempDir()
        FileStorage(dir).use { it.write(snapshotAt(10, 20)) }
        dir.listDirectoryEntries().single().resolve("index").writeText("too short")

        assertFailsWith<CorruptBlockException> { FileStorage(dir).use { it.blocks() } }
    }

    @Test
    fun `a stray file next to the blocks is ignored`() {
        val dir = tempDir()
        FileStorage(dir).use { it.write(snapshotAt(10, 20)) }
        dir.resolve("notes.txt").writeText("not a block")

        FileStorage(dir).use { assertEquals(1, it.blocks().size) }
    }
}
