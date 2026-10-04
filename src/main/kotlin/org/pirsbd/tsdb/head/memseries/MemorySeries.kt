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

    private val inOrderSeries: InOrderSeries = when (encoding) {
        ChunkEncoding.RAW -> RawInOrderSeries()
        ChunkEncoding.XOR -> throw NotImplementedError("TODO: implement if needed")
    }

    private var latestTimestamp: Long = Long.MIN_VALUE

    //TODO: iterators? azmukhamedyarov: для тех кто будет писать сериализацию с мерджем - поставьте в конце нужный вам интерфейс
    val inOrderMemorySeries: InOrderSeries
        get() = inOrderSeries

    //и тут тоже
    val outOfOrderMemorySeries: List<Sample>
        get() = outOfOrderSeries

    fun append(timestamp: Long, value: Double) {
        lock.writeLock().withLock {
            if (timestamp >= latestTimestamp) {
                inOrderSeries.insert(timestamp, value)
                latestTimestamp = timestamp;
            } else {
                addOutOfOrder(timestamp, value)
            }
        }
    }

    fun query(range: TimeRange): ArrayList<Sample> {
        lock.readLock().withLock {
            val accum: ArrayList<Sample> = ArrayList(inOrderSeries.count().toInt() + outOfOrderSeries.size)

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
        var insertionPoint = outOfOrderSeries.binarySearch { x -> x.timestamp.compareTo(timestamp) }

        if (insertionPoint < 0) {
            insertionPoint = -(insertionPoint + 1)
        }

        outOfOrderSeries.add(insertionPoint, Sample(timestamp, value))
    }
}