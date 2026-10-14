package org.pirsbd.tsdb

import org.pirsbd.tsdb.TestLabels.drain
import org.pirsbd.tsdb.TestLabels.metric
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.api.impl.DefaultTSDB
import org.pirsbd.tsdb.head.MemoryHead
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val FULL = TimeRange(Long.MIN_VALUE, Long.MAX_VALUE)
private val CPU = listOf(Matcher.Equals("__name__", "cpu"))

/** Находки финального ревью ветки. Каждый тест падал до своего фикса. */
class ReviewFindingsTest {

    private fun tempDir(): Path = Files.createTempDirectory("tsdb-review")

    private fun runParallel(threads: Int, body: (Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(threads)
        val failure = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(threads)
        for (t in 0 until threads) {
            pool.submit {
                try {
                    body(t)
                } catch (e: Throwable) {
                    failure.set(e)
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(60, TimeUnit.SECONDS)
        pool.shutdown()
        failure.get()?.let { throw it }
    }

    // Critical 1
    @Test
    fun `concurrent deletes on one flushed block all survive`() {
        DefaultTSDB(tempDir()).use { db ->
            db.appender().use { a ->
                for (t in 1L..40L) a.add(metric("cpu"), t, t.toDouble())
                a.commit()
            }
            db.flush()

            runParallel(4) { thread ->
                val from = thread * 10L + 1
                db.delete(TimeRange(from, from + 9), CPU)
            }

            val left = db.querier(FULL).use { drain(it.select(emptyList())) }
            assertEquals(emptyList(), left, "every delete must be applied, none overwritten")
        }
    }

    // Critical 2
    @Test
    fun `concurrent flushes keep every block visible to the running process`() {
        val dir = tempDir()
        val live: Int
        DefaultTSDB(dir).use { db ->
            val stop = CountDownLatch(1)
            val flushers = (1..3).map {
                Thread { while (stop.count > 0L) db.flush() }.also { it.start() }
            }

            runParallel(4) { thread ->
                for (i in 0 until 300) {
                    db.appender().use { a ->
                        a.add(metric("cpu"), (thread * 1000L + i), 1.0)
                        a.commit()
                    }
                }
            }
            stop.countDown()
            flushers.forEach { it.join(10_000) }

            live = db.querier(FULL).use { q -> drain(q.select(emptyList())).sumOf { it.second.size } }
        }

        val reopened = DefaultTSDB(dir).use { db ->
            db.querier(FULL).use { q -> drain(q.select(emptyList())).sumOf { it.second.size } }
        }
        assertEquals(4 * 300, live, "the running process must see every sample it wrote")
        assertEquals(live, reopened, "a reopened database must agree with the live one")
    }

    // Critical 3
    @Test
    fun `a duplicate timestamp older than the newest sample is overwritten, not doubled`() {
        val head = MemoryHead()
        head.append(metric("cpu"), 10, 1.0)
        head.append(metric("cpu"), 30, 3.0)
        head.append(metric("cpu"), 10, 777.0)

        val samples = drain(head.select(FULL, CPU))[0].second

        assertEquals(listOf(Sample(10, 777.0), Sample(30, 3.0)), samples)
    }

    // Critical 3, через диск: дубликат не должен попасть в блок
    @Test
    fun `the duplicate does not reach the block either`() {
        DefaultTSDB(tempDir()).use { db ->
            db.appender().use { a ->
                a.add(metric("cpu"), 10, 1.0)
                a.add(metric("cpu"), 30, 3.0)
                a.add(metric("cpu"), 10, 777.0)
                a.commit()
            }
            db.flush()

            val samples = db.querier(FULL).use { drain(it.select(CPU))[0].second }

            assertEquals(listOf(Sample(10, 777.0), Sample(30, 3.0)), samples)
        }
    }

    // Critical 4
    @Test
    fun `a series served from head and block can be iterated twice`() {
        DefaultTSDB(tempDir()).use { db ->
            db.appender().use { a -> a.add(metric("cpu"), 10, 1.0); a.commit() }
            db.flush()
            db.appender().use { a -> a.add(metric("cpu"), 20, 2.0); a.commit() }

            db.querier(FULL).use { querier ->
                querier.select(CPU).use { set ->
                    assertTrue(set.next())
                    val series = set.at()

                    val first = ArrayList<Sample>()
                    series.iterator().use { while (it.next()) first.add(it.at()) }
                    val second = ArrayList<Sample>()
                    series.iterator().use { while (it.next()) second.add(it.at()) }

                    assertEquals(listOf(Sample(10, 1.0), Sample(20, 2.0)), first)
                    assertEquals(first, second, "a Series must be re-iterable")
                }
            }
        }
    }

    // Important 8
    @Test
    fun `the head forgets a series once all of its samples are drained`() {
        val head = MemoryHead()
        head.append(metric("cpu"), 10, 1.0)
        head.append(metric("mem"), 10, 2.0)

        head.truncate(Long.MAX_VALUE)

        assertEquals(0, head.liveSeriesCount, "a drained series must leave the head")
        assertTrue(head.isEmpty)
    }

    @Test
    fun `a partially drained series stays in the head`() {
        val head = MemoryHead()
        head.append(metric("cpu"), 10, 1.0)
        head.append(metric("cpu"), 20, 2.0)

        head.truncate(20)

        assertEquals(1, head.liveSeriesCount)
        assertEquals(TimeRange(20, 20), head.range)
    }

    // Minor 10 переоценён: пустой источник обязан быть исключён явно,
    // а не через совпадение границ диапазона.
    @Test
    fun `an empty head is excluded from a query over the full range`() {
        val head = MemoryHead()

        assertTrue(head.isEmpty)
        assertEquals(emptyList(), drain(head.select(FULL, emptyList())))
    }

    @Test
    fun `a head holding one sample at Long MAX is not mistaken for empty`() {
        val head = MemoryHead()
        head.append(metric("cpu"), Long.MAX_VALUE, 1.0)

        assertEquals(false, head.isEmpty)
        assertEquals(TimeRange(Long.MAX_VALUE, Long.MAX_VALUE), head.range)
        assertEquals(1, drain(head.select(FULL, emptyList())).size)
    }

    // Important 9: источник вне диапазона не опрашивается вовсе
    @Test
    fun `only sources overlapping the range are handed to the merge`() {
        DefaultTSDB(tempDir()).use { db ->
            db.appender().use { a -> a.add(metric("cpu"), 100, 1.0); a.commit() }
            db.flush()
            db.appender().use { a -> a.add(metric("cpu"), 300, 3.0); a.commit() }
            db.flush()

            assertEquals(1, db.sourcesFor(TimeRange(250, 350)).size)
            assertEquals(2, db.sourcesFor(TimeRange(50, 350)).size)
            assertEquals(0, db.sourcesFor(TimeRange(500, 600)).size)
        }
    }
}
