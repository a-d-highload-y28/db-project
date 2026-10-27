package org.pirsbd.tsdb

import org.pirsbd.tsdb.TestLabels.drain
import org.pirsbd.tsdb.TestLabels.metric
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.api.impl.DefaultTSDB
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

private val ALL = TimeRange(0, Long.MAX_VALUE - 1)
private val CPU = listOf(Matcher.Equals("__name__", "cpu"))

class DeleteAcrossFlushTest {

    private fun tempDir(): Path = Files.createTempDirectory("tsdb-delete")

    private fun DefaultTSDB.fill(name: String, timestamps: LongRange) {
        appender().use { appender ->
            for (t in timestamps) appender.add(metric(name), t, t.toDouble())
            appender.commit()
        }
    }

    private fun DefaultTSDB.timestamps() =
        querier(ALL).use { q -> drain(q.select(emptyList())).flatMap { it.second }.map { it.timestamp } }

    @Test
    fun `1 delete after a flush hides samples that already live in a block`() {
        DefaultTSDB(tempDir()).use { db ->
            db.fill("cpu", 1L..5L)
            db.flush()

            db.delete(TimeRange(2, 3), CPU)

            assertEquals(listOf(1L, 4L, 5L), db.timestamps())
        }
    }

    @Test
    fun `2 tombstones written to a block survive a reopen`() {
        val dir = tempDir()
        DefaultTSDB(dir).use { db ->
            db.fill("cpu", 1L..5L)
            db.flush()
            db.delete(TimeRange(2, 3), CPU)
        }

        DefaultTSDB(dir).use { db ->
            assertEquals(listOf(1L, 4L, 5L), db.timestamps())
        }
    }

    @Test
    fun `3 delete spanning the head and a block hides both halves`() {
        DefaultTSDB(tempDir()).use { db ->
            db.fill("cpu", 1L..3L)
            db.flush()
            db.fill("cpu", 4L..6L)

            db.delete(TimeRange(2, 5), CPU)

            assertEquals(listOf(1L, 6L), db.timestamps())
        }
    }

    @Test
    fun `4 a series fully covered by a tombstone disappears from the result`() {
        DefaultTSDB(tempDir()).use { db ->
            db.fill("cpu", 1L..3L)
            db.fill("mem", 1L..3L)
            db.flush()

            db.delete(TimeRange(0, 100), CPU)

            val got = db.querier(ALL).use { drain(it.select(emptyList())) }
            assertEquals(listOf(metric("mem")), got.map { it.first })
        }
    }

    @Test
    fun `5 a series fully covered while still in the head also disappears`() {
        DefaultTSDB(tempDir()).use { db ->
            db.fill("cpu", 1L..3L)
            db.fill("mem", 1L..3L)

            db.delete(TimeRange(0, 100), CPU)

            val got = db.querier(ALL).use { drain(it.select(emptyList())) }
            assertEquals(listOf(metric("mem")), got.map { it.first })
        }
    }

    @Test
    fun `6 delete only touches the series matched by the matchers`() {
        DefaultTSDB(tempDir()).use { db ->
            db.fill("cpu", 1L..3L)
            db.fill("mem", 1L..3L)
            db.flush()

            db.delete(TimeRange(2, 2), CPU)

            val got = db.querier(ALL).use { drain(it.select(emptyList())) }
            assertEquals(listOf(1L, 3L), got.first { it.first == metric("cpu") }.second.map { it.timestamp })
            assertEquals(listOf(1L, 2L, 3L), got.first { it.first == metric("mem") }.second.map { it.timestamp })
        }
    }

    @Test
    fun `7 deleting twice is idempotent`() {
        DefaultTSDB(tempDir()).use { db ->
            db.fill("cpu", 1L..5L)
            db.flush()

            db.delete(TimeRange(2, 3), CPU)
            db.delete(TimeRange(2, 3), CPU)

            assertEquals(listOf(1L, 4L, 5L), db.timestamps())
        }
    }

    @Test
    fun `8 deleted samples stay deleted after a later flush`() {
        DefaultTSDB(tempDir()).use { db ->
            db.fill("cpu", 1L..5L)
            db.delete(TimeRange(2, 3), CPU)
            db.flush()

            assertEquals(listOf(1L, 4L, 5L), db.timestamps())
        }
    }
}
