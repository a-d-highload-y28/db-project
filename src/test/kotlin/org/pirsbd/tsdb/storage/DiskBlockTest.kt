package org.pirsbd.tsdb.storage

import org.pirsbd.tsdb.TestLabels.drain
import org.pirsbd.tsdb.TestLabels.metric
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.head.SnapshotSeries
import org.pirsbd.tsdb.head.SnapshotTombstone
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val ALL = TimeRange(0, 1_000)

class DiskBlockTest {

    private fun tempDir(): Path = Files.createTempDirectory("tsdb-block")

    private fun snapshot(tombstones: List<SnapshotTombstone> = emptyList()) = FixedSnapshotFor(
        listOf(
            SnapshotSeries(metric("cpu", "host" to "a"), listOf(Sample(10, 1.0), Sample(20, 2.0))),
            SnapshotSeries(metric("cpu", "host" to "b"), listOf(Sample(10, 3.0))),
            SnapshotSeries(metric("mem"), listOf(Sample(30, 4.0))),
        ),
        tombstones,
    )

    @Test
    fun `a written block reads back through select`() {
        FileBlockWriter(tempDir()).write(snapshot()).use { block ->
            val got = drain(block.select(ALL, emptyList()))

            assertEquals(3, got.size)
            assertEquals(listOf(Sample(10, 1.0), Sample(20, 2.0)), got[0].second)
        }
    }

    @Test
    fun `series come out ordered by labels`() {
        FileBlockWriter(tempDir()).write(snapshot()).use { block ->
            val got = drain(block.select(ALL, emptyList()))

            assertEquals(
                listOf(metric("cpu", "host" to "a"), metric("cpu", "host" to "b"), metric("mem")),
                got.map { it.first },
            )
        }
    }

    @Test
    fun `matchers filter through postings`() {
        FileBlockWriter(tempDir()).write(snapshot()).use { block ->
            val got = drain(block.select(ALL, listOf(Matcher.Equals("host", "b"))))

            assertEquals(listOf(metric("cpu", "host" to "b")), got.map { it.first })
        }
    }

    @Test
    fun `the time range filters samples`() {
        FileBlockWriter(tempDir()).write(snapshot()).use { block ->
            val got = drain(block.select(TimeRange(15, 25), emptyList()))

            assertEquals(1, got.size)
            assertEquals(listOf(Sample(20, 2.0)), got[0].second)
        }
    }

    @Test
    fun `the block lands in its own folder with the four expected files`() {
        val dir = tempDir()
        val block = FileBlockWriter(dir).write(snapshot())

        val folder = dir.listDirectoryEntries().single()
        assertTrue(folder.resolve("meta.json").exists())
        assertTrue(folder.resolve("index").exists())
        assertTrue(folder.resolve("chunks/000001").exists())
        assertTrue(folder.resolve("tombstones").exists())
        assertEquals(block.meta.ulid, folder.fileName.toString())
        block.close()
    }

    @Test
    fun `no tmp folder is left behind`() {
        val dir = tempDir()
        FileBlockWriter(dir).write(snapshot()).close()

        assertEquals(
            emptyList(),
            dir.listDirectoryEntries().filter { it.fileName.toString().endsWith(".tmp") },
        )
    }

    @Test
    fun `meta carries the range and the stats`() {
        FileBlockWriter(tempDir()).write(snapshot()).use { block ->
            assertEquals(TimeRange(10, 30), block.meta.range)
            assertEquals(3, block.meta.stats.numSeries)
            assertEquals(4, block.meta.stats.numSamples)
            assertEquals(3, block.meta.stats.numChunks)
        }
    }

    @Test
    fun `snapshot tombstones are written into the block and hide samples`() {
        val tombstone = SnapshotTombstone(metric("cpu", "host" to "a"), TimeRange(15, 25))

        FileBlockWriter(tempDir()).write(snapshot(listOf(tombstone))).use { block ->
            val got = drain(block.select(ALL, emptyList()))

            assertEquals(listOf(Sample(10, 1.0)), got[0].second)
        }
    }

    @Test
    fun `addTombstones hides samples and survives reopening the folder`() {
        val dir = tempDir()
        val written = FileBlockWriter(dir).write(snapshot())
        val offsets = written.offsetsOf(listOf(Matcher.Equals("host", "a")))
        written.addTombstones(offsets.map { org.pirsbd.tsdb.storage.codec.Tombstone(it, TimeRange(0, 15)) })
        written.close()

        DiskBlock(dir.listDirectoryEntries().single()).use { reopened ->
            val got = drain(reopened.select(ALL, emptyList()))

            assertEquals(listOf(Sample(20, 2.0)), got[0].second)
        }
    }

    /**
     * Бьёт addTombstones напрямую. Через DefaultTSDB.delete этот лок уже не
     * проверить: там потоки сериализуются локом БД и до гонки не доходят.
     */
    @Test
    fun `concurrent addTombstones calls do not overwrite each other`() {
        val dir = tempDir()
        FileBlockWriter(dir).write(snapshot()).use { block ->
            val offset = block.offsetsOf(listOf(Matcher.Equals("host", "a"))).single()
            val threads = 8
            val pool = Executors.newFixedThreadPool(threads)
            val latch = CountDownLatch(threads)

            for (t in 0 until threads) {
                pool.submit {
                    try {
                        val from = t * 100L
                        block.addTombstones(
                            listOf(org.pirsbd.tsdb.storage.codec.Tombstone(offset, TimeRange(from, from + 10))),
                        )
                    } finally {
                        latch.countDown()
                    }
                }
            }
            assertTrue(latch.await(30, TimeUnit.SECONDS), "threads timed out")
            pool.shutdown()

            val reopened = DiskBlock(dir.listDirectoryEntries().single())
            val ranges = reopened.offsetsOf(listOf(Matcher.Equals("host", "a")))
            assertEquals(1, ranges.size)
            // Каждый из 8 потоков записал свой непересекающийся диапазон.
            // Если лок не работает, часть записей теряется.
            assertEquals(threads, reopened.tombstoneCountFor(offset))
            reopened.close()
        }
    }

    @Test
    fun `ulids sort chronologically so folder order is creation order`() {
        val dir = tempDir()
        val first = FileBlockWriter(dir).write(snapshot()).also { it.close() }
        val second = FileBlockWriter(dir).write(snapshot()).also { it.close() }

        assertTrue(first.meta.ulid < second.meta.ulid, "${first.meta.ulid} !< ${second.meta.ulid}")
        assertEquals(26, first.meta.ulid.length)
    }
}
