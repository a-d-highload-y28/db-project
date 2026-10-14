package org.pirsbd.tsdb.head

import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.index.SeriesRef
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

internal class Tombstones {
    private val tombstones: HashMap<SeriesRef, ArrayList<TimeRange>> = HashMap()
    private val lock: ReentrantReadWriteLock = ReentrantReadWriteLock()

    fun append(ref: SeriesRef, timeRange: TimeRange) {
        lock.writeLock().withLock {
            if (tombstones.putIfAbsent(ref, arrayListOf(timeRange)) == null)
                return

            val ranges = tombstones[ref]!!
            var scanIndex = kostylLowerBoundEndRangeSearch(-1, ranges.size, timeRange.minTime, ranges)
            val startIndex = scanIndex

            var minTime = timeRange.minTime
            var maxTime = timeRange.maxTime

            while (scanIndex < ranges.size && ranges[scanIndex].overlaps(timeRange)) {
                minTime = Math.min(minTime, ranges[scanIndex].minTime)
                maxTime = Math.max(maxTime, ranges[scanIndex].maxTime)
                scanIndex += 1
            }

            ranges.subList(startIndex, scanIndex).clear()
            ranges.add(startIndex, TimeRange(minTime, maxTime))
        }
    }

    fun query(refs: List<SeriesRef>): Map<SeriesRef, List<TimeRange>> {
        return lock.readLock().withLock {
            val map = HashMap<SeriesRef, ArrayList<TimeRange>>()
            for (ref in refs) {
                val ranges = tombstones.getOrDefault(ref, ArrayList())
                val copy = ArrayList<TimeRange>(ranges.size)
                copy.addAll(ranges)

                map[ref] = copy
            }

            return@withLock map
        }
    }

    private fun kostylLowerBoundEndRangeSearch(
        left: Int,
        right: Int,
        rangeStart: Long,
        range: ArrayList<TimeRange>
    ): Int {
        var l = left;
        var r = right;

        while (r - l > 1) {
            var mid = l + (r - l) / 2
            var elem = range[mid].maxTime

            if (elem < rangeStart)
                l = mid;
            else
                r = mid
        }

        return r
    }
}