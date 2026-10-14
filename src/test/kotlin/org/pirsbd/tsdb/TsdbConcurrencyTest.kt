package org.pirsbd.tsdb

import org.pirsbd.tsdb.TestLabels.drain
import org.pirsbd.tsdb.TestLabels.metric
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.api.impl.DefaultTSDB
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val ALL = TimeRange(0, Long.MAX_VALUE - 1)

class TsdbConcurrencyTest {

    private val threads = 8
    private val perThread = 200

    @Test
    fun `1 concurrent writers to one series keep every sample`() {
        DefaultTSDB(Files.createTempDirectory("tsdb-conc")).use { db ->
            runParallel { thread ->
                db.appender().use { appender ->
                    for (i in 0 until perThread) {
                        appender.add(metric("cpu"), (thread * perThread + i).toLong(), i.toDouble())
                    }
                    appender.commit()
                }
            }

            val samples = db.querier(ALL).use { drain(it.select(emptyList()))[0].second }

            assertEquals(threads * perThread, samples.size)
            assertEquals(samples.map { it.timestamp }.sorted(), samples.map { it.timestamp })
        }
    }

    @Test
    fun `2 concurrent writers to distinct series keep every series`() {
        DefaultTSDB(Files.createTempDirectory("tsdb-conc")).use { db ->
            runParallel { thread ->
                db.appender().use { appender ->
                    for (i in 0 until perThread) {
                        appender.add(metric("cpu", "thread" to "$thread"), i.toLong(), i.toDouble())
                    }
                    appender.commit()
                }
            }

            assertEquals(threads, db.querier(ALL).use { drain(it.select(emptyList())).size })
        }
    }

    @Test
    fun `3 a reader running next to writers never throws`() {
        DefaultTSDB(Files.createTempDirectory("tsdb-conc")).use { db ->
            val failure = AtomicReference<Throwable?>(null)
            val stop = CountDownLatch(1)
            val reader = Thread {
                try {
                    while (stop.count > 0L) {
                        db.querier(ALL).use { drain(it.select(emptyList())) }
                    }
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
            reader.start()

            runParallel { thread ->
                db.appender().use { appender ->
                    for (i in 0 until perThread) {
                        appender.add(metric("cpu", "thread" to "$thread"), i.toLong(), i.toDouble())
                    }
                    appender.commit()
                }
            }

            stop.countDown()
            reader.join(10_000)

            assertNull(failure.get())
        }
    }

    @Test
    fun `4 a flush running next to writers loses nothing`() {
        DefaultTSDB(Files.createTempDirectory("tsdb-conc")).use { db ->
            val failure = AtomicReference<Throwable?>(null)
            val stop = CountDownLatch(1)
            val flusher = Thread {
                try {
                    while (stop.count > 0L) db.flush()
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
            flusher.start()

            runParallel { thread ->
                db.appender().use { appender ->
                    for (i in 0 until perThread) {
                        appender.add(metric("cpu", "thread" to "$thread"), (thread * perThread + i).toLong(), 1.0)
                    }
                    appender.commit()
                }
            }

            stop.countDown()
            flusher.join(10_000)
            assertNull(failure.get())

            val total = db.querier(ALL).use { q ->
                drain(q.select(emptyList())).sumOf { it.second.size }
            }
            assertEquals(threads * perThread, total)
        }
    }

    private fun runParallel(body: (Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(threads)
        val failure = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(threads)
        for (thread in 0 until threads) {
            pool.submit {
                try {
                    body(thread)
                } catch (t: Throwable) {
                    failure.set(t)
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(60, TimeUnit.SECONDS)
        pool.shutdown()
        failure.get()?.let { throw it }
    }
}
