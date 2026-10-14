package org.pirsbd.tsdb.head

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.SeriesSet
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.api.impl.MapSeriesSet
import org.pirsbd.tsdb.storage.Block
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.withLock

class MemoryHead : Head {
    @OptIn(ExperimentalAtomicApi::class)
    private val minTime: AtomicLong = AtomicLong(Long.MAX_VALUE)
    @OptIn(ExperimentalAtomicApi::class)
    private val maxTime: AtomicLong = AtomicLong(Long.MIN_VALUE)

    private val chunk: MemoryChunk = MemoryChunk()

    private var isClosed = false
    private val lock: ReadWriteLock = ReentrantReadWriteLock() //А нужно ли

    override val range: TimeRange
        get() = TODO("Not yet implemented")


    override fun append(labels: Labels, timestamp: Long, value: Double) {
        lock.readLock().withLock {
            if (isClosed) { return }

            chunk.append(labels, timestamp, value)
            updateRange(timestamp)
        }
    }

    override fun query(
        range: TimeRange,
        matchers: List<Matcher>
    ): SeriesSet {
        val result = lock.readLock().withLock {
            return@withLock chunk.query(matchers, range)
        }

        return MapSeriesSet(result)
    }

    override fun delete(
        range: TimeRange,
        matchers: List<Matcher>
    ) {
        lock.readLock().withLock {
            if (isClosed) { return }
            chunk.delete(matchers, range)
        }
    }

    override fun trim(minTime: TimeRange): Block {
        TODO("trim")
    }


    override fun close() {
        //TODO: try lock and skip if some thread starts to flush?
        lock.writeLock().withLock {
            //TODO: min
            isClosed = true
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun updateRange(timestamp: Long) {
        //min
        while (true) {
            val time = minTime.load()

            if (timestamp < time)
            {
                if (minTime.compareAndSet(time, timestamp))
                    break

                else continue
            }

            break
        }

        //max
        while (true) {
            val time = maxTime.load()

            if (timestamp > time)
            {
                if (maxTime.compareAndSet(time, timestamp))
                    break

                else continue
            }

            break
        }
    }
}

