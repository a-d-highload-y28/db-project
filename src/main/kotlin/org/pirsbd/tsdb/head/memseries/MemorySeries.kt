package org.pirsbd.tsdb.head.memseries

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.chunks.ChunkEncoding
import org.pirsbd.tsdb.index.SeriesRef
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

internal class MemorySeries(val ref: SeriesRef, val labels: Labels) {
    private val outOfOrderSeries: ArrayList<Sample> = ArrayList(1)

    private val lock: ReentrantReadWriteLock = ReentrantReadWriteLock()

    private val encoding: ChunkEncoding = ChunkEncoding.RAW //TODO: strategies.

    private var inOrderSeries: InOrderSeries = newInOrderSeries()

    private var latestTimestamp: Long = Long.MIN_VALUE

    //TODO: iterators? azmukhamedyarov: для тех кто будет писать сериализацию с мерджем - поставьте в конце нужный вам интерфейс
    val inOrderMemorySeries: InOrderSeries
        get() = inOrderSeries

    //и тут тоже
    val outOfOrderMemorySeries: List<Sample>
        get() = outOfOrderSeries

    /**
     * Дубликат пары (серия, timestamp) перезаписывается: последний выигрывает.
     * Это делает запись идемпотентной, что нужно для ретраев в лабе 3 и для
     * переигрывания на реплике в лабе 4.
     */
    fun append(timestamp: Long, value: Double) {
        lock.writeLock().withLock {
            if (timestamp > latestTimestamp) {
                inOrderSeries.insert(timestamp, value)
                latestTimestamp = timestamp
                return
            }

            // Дубликат надо искать в обоих буферах. Раньше проверялся только
            // последний in-order сэмпл, поэтому повтор таймстемпа, который уже
            // лежит в середине in-order буфера, уезжал в out-of-order список и
            // при чтении вылезал второй точкой на том же таймстемпе.
            val inOrder = inOrderSeries.indexOf(timestamp)
            if (inOrder >= 0) {
                inOrderSeries.replaceAt(inOrder, timestamp, value)
                return
            }

            addOutOfOrder(timestamp, value)
        }
    }

    /** Диапазон того, что серия держит сейчас. O(1), без обхода сэмплов. */
    fun currentRange(): TimeRange? = lock.readLock().withLock {
        val mins = listOfNotNull(inOrderSeries.firstTimestamp(), outOfOrderSeries.firstOrNull()?.timestamp)
        val maxs = listOfNotNull(inOrderSeries.lastTimestamp(), outOfOrderSeries.lastOrNull()?.timestamp)
        if (mins.isEmpty()) null else TimeRange(mins.min(), maxs.max())
    }

    fun isEmptyNow(): Boolean = lock.readLock().withLock {
        inOrderSeries.count() == 0 && outOfOrderSeries.isEmpty()
    }

    fun query(range: TimeRange): ArrayList<Sample> = lock.readLock().withLock { queryLocked(range) }

    /**
     * Отдаёт сэмплы строго меньше upTo и убирает их из серии.
     * Остаток перекладывается в свежий буфер: RawInOrderSeries умеет только
     * дописывать в конец, вырезать из середины он не умеет.
     */
    fun drainBelow(upTo: Long): List<Sample> {
        lock.writeLock().withLock {
            val all = queryLocked(TimeRange(Long.MIN_VALUE, Long.MAX_VALUE))
            if (all.isEmpty()) return emptyList()

            val drained = ArrayList<Sample>()
            val kept = ArrayList<Sample>()
            for (sample in all) {
                if (sample.timestamp < upTo) drained.add(sample) else kept.add(sample)
            }
            if (drained.isEmpty()) return emptyList()

            val fresh = newInOrderSeries()
            for (sample in kept) fresh.insert(sample.timestamp, sample.value)
            inOrderSeries = fresh
            outOfOrderSeries.clear()
            latestTimestamp = if (kept.isEmpty()) Long.MIN_VALUE else kept.last().timestamp

            return drained
        }
    }

    private fun newInOrderSeries(): InOrderSeries = when (encoding) {
        ChunkEncoding.RAW -> RawInOrderSeries()
        ChunkEncoding.XOR -> throw NotImplementedError("TODO: implement if needed")
    }

    private fun queryLocked(range: TimeRange): ArrayList<Sample> {
        val accum: ArrayList<Sample> = ArrayList(inOrderSeries.count() + outOfOrderSeries.size)

        var outOfOrderPos = kostylOutOfOrderBinLeftSearch(-1, outOfOrderSeries.size, range.minTime)

        val inOrderIterator = inOrderSeries.iterator()

        while (inOrderIterator.hasNext() && inOrderIterator.peek().timestamp < range.minTime) {
            inOrderIterator.next()
        }

        while (inOrderIterator.hasNext() && inOrderIterator.peek().timestamp <= range.maxTime && outOfOrderPos < outOfOrderSeries.size && outOfOrderSeries[outOfOrderPos].timestamp <= range.maxTime) {
            val inOrderMetric = inOrderIterator.peek()
            val outOfOrderMetric = outOfOrderSeries[outOfOrderPos]

            if (inOrderMetric.timestamp <= outOfOrderMetric.timestamp) {
                accum.add(inOrderMetric)
                inOrderIterator.next()
            } else {
                accum.add(outOfOrderMetric)
                outOfOrderPos += 1
            }
        }

        while (inOrderIterator.hasNext() && inOrderIterator.peek().timestamp <= range.maxTime) {
            val sample = inOrderIterator.peek()
            inOrderIterator.next()

            accum.add(sample)
        }

        while (outOfOrderPos < outOfOrderSeries.size && outOfOrderSeries[outOfOrderPos].timestamp <= range.maxTime) {
            val outOfOrderMetric = outOfOrderSeries[outOfOrderPos]

            accum.add(outOfOrderMetric)
            outOfOrderPos += 1
        }

        return accum
    }


    private fun kostylOutOfOrderBinLeftSearch(left: Int, right: Int, timestamp: Long): Int {
        var l = left;
        var r = right;

        while (r - l > 1) {
            var mid = l + (r - l) / 2
            var elem = outOfOrderSeries[mid].timestamp

            if (elem < timestamp)
                l = mid;
            else
                r = mid
        }

        return r
    }


    // O(n)
    private fun addOutOfOrder(timestamp: Long, value: Double) {
        val found = outOfOrderSeries.binarySearch { x -> x.timestamp.compareTo(timestamp) }

        if (found >= 0) {
            outOfOrderSeries[found] = Sample(timestamp, value)
        } else {
            outOfOrderSeries.add(-(found + 1), Sample(timestamp, value))
        }
    }
}