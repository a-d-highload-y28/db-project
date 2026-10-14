package org.pirsbd.tsdb

import org.pirsbd.tsdb.TestLabels.drain
import org.pirsbd.tsdb.TestLabels.metric
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.TSDB
import org.pirsbd.tsdb.api.TSDBConfig
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.api.impl.DefaultTSDB
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val ALL = TimeRange(0, Long.MAX_VALUE - 1)

class TsdbEndToEndTest {

    private fun tempDir(): Path = Files.createTempDirectory("tsdb-e2e")

    private fun TSDB.put(vararg samples: Triple<String, Long, Double>) {
        appender().use { appender ->
            for ((name, timestamp, value) in samples) appender.add(metric(name), timestamp, value)
            appender.commit()
        }
    }

    private fun TSDB.read(vararg matchers: Matcher) =
        querier(ALL).use { drain(it.select(matchers.toList())) }

    @Test
    fun `1 append then query without a flush`() {
        DefaultTSDB(tempDir()).use { db ->
            db.put(Triple("cpu", 10L, 1.0), Triple("cpu", 20L, 2.0))

            assertEquals(listOf(Sample(10, 1.0), Sample(20, 2.0)), db.read()[0].second)
        }
    }

    @Test
    fun `2 append then flush then query`() {
        DefaultTSDB(tempDir()).use { db ->
            db.put(Triple("cpu", 10L, 1.0))
            db.flush()

            assertEquals(listOf(Sample(10, 1.0)), db.read()[0].second)
        }
    }

    @Test
    fun `3 a query merges the head and a block`() {
        DefaultTSDB(tempDir()).use { db ->
            db.put(Triple("cpu", 10L, 1.0))
            db.flush()
            db.put(Triple("cpu", 20L, 2.0))

            assertEquals(listOf(Sample(10, 1.0), Sample(20, 2.0)), db.read()[0].second)
        }
    }

    @Test
    fun `4 data survives close and reopen`() {
        val dir = tempDir()
        DefaultTSDB(dir).use { it.put(Triple("cpu", 10L, 1.0)) }

        DefaultTSDB(dir).use { db ->
            assertEquals(listOf(Sample(10, 1.0)), db.read()[0].second)
        }
    }

    @Test
    fun `5 two flushes make two blocks and a query covers both`() {
        val dir = tempDir()
        DefaultTSDB(dir).use { db ->
            db.put(Triple("cpu", 10L, 1.0))
            db.flush()
            db.put(Triple("cpu", 20L, 2.0))
            db.flush()

            assertEquals(2, dir.listDirectoryEntries().size)
            assertEquals(listOf(Sample(10, 1.0), Sample(20, 2.0)), db.read()[0].second)
        }
    }

    @Test
    fun `6 a narrow range only touches one block`() {
        DefaultTSDB(tempDir()).use { db ->
            db.put(Triple("cpu", 100L, 1.0))
            db.flush()
            db.put(Triple("cpu", 300L, 3.0))
            db.flush()

            val got = db.querier(TimeRange(250, 350)).use { drain(it.select(emptyList())) }

            assertEquals(listOf(Sample(300, 3.0)), got[0].second)
        }
    }

    @Test
    fun `7 empty matchers select every series`() {
        DefaultTSDB(tempDir()).use { db ->
            db.put(Triple("cpu", 10L, 1.0), Triple("mem", 10L, 2.0))

            assertEquals(listOf(metric("cpu"), metric("mem")), db.read().map { it.first })
        }
    }

    @Test
    fun `8 rollback does not apply the batch`() {
        DefaultTSDB(tempDir()).use { db ->
            db.appender().use { appender ->
                appender.add(metric("cpu"), 10, 1.0)
                appender.rollback()
            }

            assertEquals(emptyList(), db.read())
        }
    }

    @Test
    fun `9 an appender closed without commit drops the batch`() {
        DefaultTSDB(tempDir()).use { db ->
            db.appender().use { it.add(metric("cpu"), 10, 1.0) }

            assertEquals(emptyList(), db.read())
        }
    }

    @Test
    fun `10 an empty database answers a query instead of crashing`() {
        DefaultTSDB(tempDir()).use { db ->
            db.querier(ALL).use { querier ->
                querier.select(emptyList()).use { assertFalse(it.next()) }
            }
            assertEquals(emptyList(), db.read())
        }
    }

    @Test
    fun `11 a sample without __name__ is rejected on add`() {
        DefaultTSDB(tempDir()).use { db ->
            db.appender().use { appender ->
                assertFailsWith<IllegalArgumentException> {
                    appender.add(TestLabels.of("host" to "a"), 10, 1.0)
                }
            }
        }
    }

    @Test
    fun `12 the auto flush threshold is driven by sample timestamps`() {
        val dir = tempDir()
        DefaultTSDB(dir, TSDBConfig(headWindow = 1_000)).use { db ->
            db.put(Triple("cpu", 0L, 1.0))
            assertEquals(0, dir.listDirectoryEntries().size)

            db.put(Triple("cpu", 5_000L, 2.0))

            assertTrue(dir.listDirectoryEntries().isNotEmpty(), "crossing headWindow must flush")
            assertEquals(listOf(Sample(0, 1.0), Sample(5_000, 2.0)), db.read()[0].second)
        }
    }

    @Test
    fun `13 a timestamp near Long MAX does not overflow the flush threshold`() {
        DefaultTSDB(tempDir(), TSDBConfig(headWindow = 1_000)).use { db ->
            db.put(Triple("cpu", Long.MAX_VALUE - 10, 1.0))

            assertEquals(1, db.read().size)
        }
    }

    @Test
    fun `14 a span wider than Long can hold still flushes instead of crashing`() {
        DefaultTSDB(tempDir(), TSDBConfig(headWindow = 1_000)).use { db ->
            db.put(Triple("cpu", Long.MIN_VALUE, 1.0), Triple("cpu", Long.MAX_VALUE - 1, 2.0))

            val got = db.querier(TimeRange(Long.MIN_VALUE, Long.MAX_VALUE - 1))
                .use { drain(it.select(emptyList())) }

            assertEquals(listOf(Long.MIN_VALUE, Long.MAX_VALUE - 1), got[0].second.map { it.timestamp })
        }
    }

    @Test
    fun `15 delete before a flush hides the samples`() {
        DefaultTSDB(tempDir()).use { db ->
            db.put(Triple("cpu", 10L, 1.0), Triple("cpu", 20L, 2.0), Triple("cpu", 30L, 3.0))

            db.delete(TimeRange(15, 25), listOf(Matcher.Equals("__name__", "cpu")))

            assertEquals(listOf(Sample(10, 1.0), Sample(30, 3.0)), db.read()[0].second)
        }
    }

    @Test
    fun `16 the head wins on an equal timestamp`() {
        DefaultTSDB(tempDir()).use { db ->
            db.put(Triple("cpu", 10L, 1.0))
            db.flush()
            db.put(Triple("cpu", 10L, 999.0))

            assertEquals(listOf(Sample(10, 999.0)), db.read()[0].second)
        }
    }
}
