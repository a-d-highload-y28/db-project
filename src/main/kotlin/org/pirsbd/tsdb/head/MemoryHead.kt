package org.pirsbd.tsdb.head

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.SeriesSet
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.api.impl.SortedMapSeriesSet
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.withLock

@OptIn(ExperimentalAtomicApi::class)
class MemoryHead(stripeSize: Int = 16) : Head {
    private val minTime: AtomicLong = AtomicLong(Long.MAX_VALUE)
    private val maxTime: AtomicLong = AtomicLong(Long.MIN_VALUE)

    private val chunk: MemoryChunk = MemoryChunk(stripeSize)

    private var isClosed = false

    /**
     * Запись берёт readLock, а close() writeLock. Лок защищает не данные
     * (за них отвечают striped-локи внутри), а флаг закрытия: пока идёт
     * хоть одна запись, close() не проходит.
     */
    private val lock: ReadWriteLock = ReentrantReadWriteLock()

    override val range: TimeRange
        get() {
            val min = minTime.load()
            val max = maxTime.load()
            return if (min > max) TimeRange.EMPTY else TimeRange(min, max)
        }

    override fun append(labels: Labels, timestamp: Long, value: Double) {
        lock.readLock().withLock {
            check(!isClosed) { "head is closed" }
            chunk.append(labels, timestamp, value)
            updateRange(timestamp)
        }
    }

    override fun select(range: TimeRange, matchers: List<Matcher>): SeriesSet {
        val result = lock.readLock().withLock { chunk.query(matchers, range) }
        return SortedMapSeriesSet(result)
    }

    override fun delete(range: TimeRange, matchers: List<Matcher>) {
        lock.readLock().withLock {
            check(!isClosed) { "head is closed" }
            chunk.delete(matchers, range)
        }
    }

    /**
     * Идёт под writeLock, поэтому записи на время флаша блокируются. Для MVP
     * это приемлемое упрощение, в Prometheus сделано тоньше.
     */
    override fun truncate(upTo: Long): HeadSnapshot? {
        lock.writeLock().withLock {
            val series = chunk.drain(upTo)
            if (series.isEmpty()) return null

            val tombstones = chunk.drainTombstones()

            val remaining = chunk.currentRange()
            minTime.store(remaining?.minTime ?: Long.MAX_VALUE)
            maxTime.store(remaining?.maxTime ?: Long.MIN_VALUE)

            return MaterializedSnapshot(series, tombstones)
        }
    }

    override fun close() {
        lock.writeLock().withLock { isClosed = true }
    }

    private fun updateRange(timestamp: Long) {
        while (true) {
            val current = minTime.load()
            if (timestamp >= current) break
            if (minTime.compareAndSet(current, timestamp)) break
        }
        while (true) {
            val current = maxTime.load()
            if (timestamp <= current) break
            if (maxTime.compareAndSet(current, timestamp)) break
        }
    }
}
