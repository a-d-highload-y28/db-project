package org.pirsbd.tsdb.head

import org.pirsbd.tsdb.api.Label
import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.SeriesSet
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.chunks.ChunkEncoding
import org.pirsbd.tsdb.index.SeriesRef
import org.pirsbd.tsdb.storage.Block
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

internal sealed class MemoryHead : Head {

    override val range: TimeRange
        get() = TODO("Not yet implemented")

    override fun append(labels: Labels, timestamp: Long, value: Double) {
        TODO("Not yet implemented")
    }

    override fun query(
        range: TimeRange,
        matchers: List<Matcher>
    ): SeriesSet {
        TODO("Not yet implemented")
    }

    override fun delete(
        range: TimeRange,
        matchers: List<Matcher>
    ) {
        TODO("Not yet implemented")
    }

    override fun truncate(minTime: Long): Block? {
        TODO("Not yet implemented")
    }

    override fun close() {
        TODO("Not yet implemented")
    }
}

internal class StripeCell {
    val lock: ReentrantReadWriteLock = ReentrantReadWriteLock()
    val labelHashMap: HashMap<Int, MemorySeries> = HashMap()
    val refMap: HashMap<SeriesRef, MemorySeries> = HashMap()
}

//TODO: proper label set here
internal class MemorySeries(val ref: SeriesRef, val labels: List<Label>) {
    private val inOrderSeries: ByteBuffer = ByteBuffer.allocate(8 + (Long.SIZE_BYTES * Double.SIZE_BYTES) * 2)
        .order(ByteOrder.BIG_ENDIAN)
        .putLong(0)

    private val outOfOrderSeries: ArrayList<MetricValue> = ArrayList(1)

    private val lock: ReentrantReadWriteLock = ReentrantReadWriteLock()

    private val encoding: ChunkEncoding = ChunkEncoding.RAW //TODO: strategies.

    private var latestTimestamp: Long = Long.MIN_VALUE

    fun append(timestamp: Long, value: Double) {
        //TODO: suspend
        lock.writeLock().withLock {
            if (timestamp >= latestTimestamp) {
                writeRaw(timestamp, value)
                latestTimestamp = timestamp;
            } else {
                addOutOfOrder(timestamp, value)
            }
        }
    }

    fun query(range: TimeRange): ArrayList<MetricValue> {
        //TODO: suspend?

        var outOfOrderPos = kostylOutOfOrderBinLeftSearch(0, outOfOrderSeries.size, range.minTime)

        var inOrderOffset = Long.SIZE_BYTES
        var inOrderPos = 0

        while (inOrderSeries.getLong(inOrderOffset) < range.minTime) {
            inOrderOffset += Long.SIZE_BYTES + Double.SIZE_BYTES
            inOrderPos += 1
        }


        val accum: ArrayList<MetricValue> = ArrayList(inOrderSeries.getLong(0).toInt() + outOfOrderSeries.size)

        while (inOrderPos < inOrderSeries.getLong(0) && inOrderSeries.getLong(inOrderOffset) <= range.maxTime
            && outOfOrderPos < outOfOrderSeries.size && outOfOrderSeries[outOfOrderPos].timestamp <= range.maxTime
        ) {
            val inOrderTimestamp = inOrderSeries.getLong(inOrderOffset)
            val inOrderValue = inOrderSeries.getDouble()

            val outOfOrderMetric = outOfOrderSeries[outOfOrderPos]

            if (inOrderTimestamp <= outOfOrderMetric.timestamp) {
                accum.add(MetricValue(inOrderTimestamp, inOrderValue))
                inOrderPos += 1
            } else {
                accum.add(outOfOrderMetric)
                outOfOrderPos += 1
            }
        }

        while (inOrderPos < inOrderSeries.getLong(0) && inOrderSeries.getLong(inOrderOffset) <= range.maxTime) {
            val inOrderTimestamp = inOrderSeries.getLong(inOrderOffset)
            val inOrderValue = inOrderSeries.getDouble()

            accum.add(MetricValue(inOrderTimestamp, inOrderValue))
            inOrderPos += 1
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


    private fun writeRaw(timestamp: Long, value: Double) {
        var count = inOrderSeries.getLong(0)
        count += 1

        inOrderSeries.putLong(timestamp)
        inOrderSeries.putDouble(value)

        inOrderSeries.putLong(-1, count)
    }

    // O(n)
    private fun addOutOfOrder(timestamp: Long, value: Double) {
        var insertionPoint = outOfOrderSeries.binarySearch { x -> x.timestamp.compareTo(timestamp) }

        if (insertionPoint < 0) {
            insertionPoint = -(insertionPoint + 1)
        }

        outOfOrderSeries.add(insertionPoint, MetricValue(timestamp, value))
    }
}

data class MetricValue(val timestamp: Long, val value: Double)

internal class Stripe(size: Int) {
    private val shards: Array<StripeCell> = Array(size, { _ -> StripeCell() })

    fun appendSeries(series: MemorySeries, ref: SeriesRef) {
        val refIndex = getIndex(ref)
        val labelHashIndex = getIndex(series.labels)

        val refCell = shards[refIndex]

        refCell.lock.writeLock().withLock {
            refCell.refMap.putIfAbsent(ref, series)
        }

        var hashCell = shards[labelHashIndex]

        hashCell.lock.writeLock().withLock {
            hashCell.refMap.putIfAbsent(ref, series)
        }
    }

    fun query(refs: List<SeriesRef>): Map<SeriesRef, MemorySeries> {
        //TODO: Batch locks
        val accum: HashMap<SeriesRef, MemorySeries> = HashMap(refs.size)

        for (ref in refs) {
            val index = getIndex(ref)
            val cell = shards[index]

            cell.lock.readLock().withLock {
                if (cell.refMap[ref] != null)
                    accum[ref] = cell.refMap[ref]!!
            }
        }

        return accum
    }

    //TODO: no sort
    fun query(labels: List<Labels>): Map<Labels, MemorySeries> {
        val accum: HashMap<Labels, MemorySeries> = HashMap(labels.size)

        for (labelSet in labels) {
            val index = getIndex(labelSet)
            val hash = hash(labelSet)
            val cell = shards[index]

            cell.lock.readLock().withLock {
                if (cell.labelHashMap[hash] != null)
                    accum[labelSet] = cell.labelHashMap[hash]!! //TODO: check
            }
        }

        return accum
    }

    private fun getIndex(ref: SeriesRef): Int {
        return Math.floorMod(hash(ref), shards.size)
    }

    private fun getIndex(labels: List<Label>): Int {
        return Math.floorMod(hash(labels), shards.size)  //TODO: proper hash
    }

    private fun hash(ref: SeriesRef): Int {
        return ref.value.hashCode()
    }

    private fun hash(labels: List<Label>): Int {
        return labels.hashCode()
    }
}


internal class Sequence(val start: Long, val step: Long) {
    private val counter: AtomicLong = AtomicLong(start)

    fun getId(): Long {
        return counter.getAndAdd(step)
    }
}

//Only '=' operation is supported
internal class PostingList {
    private val lock: ReentrantReadWriteLock = ReentrantReadWriteLock()
    private val labelRefMap: HashMap<Label, ArrayList<SeriesRef>> = HashMap()

    fun append(labels: Labels, ref: SeriesRef) {
        lock.writeLock().withLock {
            for (label in labels) {
                val seriesRef = labelRefMap.putIfAbsent(label, arrayListOf(ref))

                if (seriesRef != null) {
                    var index = seriesRef.binarySearch { x -> x.value.compareTo(ref.value) }

                    if (index < 0) {
                        index = -(index + 1)
                        seriesRef.add(index, ref)
                    }
                }
            }
        }
    }

    fun query(labels: Labels): List<SeriesRef> {
        val series: ArrayList<List<SeriesRef>> = ArrayList()
        var shortestListIndex = 0
        var shortedListEntryCount = Int.MAX_VALUE
        val result: ArrayList<SeriesRef> = ArrayList()

        lock.readLock().withLock {
            for ((index, label) in labels.withIndex()) {
                if (labelRefMap.containsKey(label)) {
                    val list = labelRefMap.getValue(label)
                    series.add(list)

                    if (list.size < shortedListEntryCount) {
                        shortestListIndex = index;
                        shortedListEntryCount = list.size
                    }
                } else {
                    return emptyList()
                }
            }
            val shortestRefList = series[shortestListIndex]


            for (ref in shortestRefList) {
                var count: Int = 1
                for ((index, refList) in series.withIndex()) {
                    if (index == shortestListIndex)
                        continue

                    if (refList.binarySearch { x -> x.value.compareTo(ref.value) } >= 0)
                        count++
                }

                if (count == series.size)
                    result.add(ref)
            }

            return result
        }
    }
}

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
                map[ref] = tombstones.getOrDefault(ref, ArrayList())
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

internal class HeadChunk {
    private val minTime: AtomicLong = AtomicLong(Long.MAX_VALUE)
    private val maxTime: AtomicLong = AtomicLong(0)

    private val sequence: Sequence = Sequence(1, 1)
    private val stripe: Stripe = Stripe(StripeSize)
    private val postingList: PostingList = PostingList()
    private val tombstones: Tombstones = Tombstones()

    fun append(labels: Labels, timestamp: Long, value: Double) {
        //TODO: fix the race on insert
        val ref = SeriesRef(sequence.getId())

        val res = stripe.query(listOf(labels)) //TODO: proper interfaces list
        val series: MemorySeries

        val newSeries = res.containsKey(labels)

        if (newSeries == false) {
            series = MemorySeries(ref, labels)
            stripe.appendSeries(series, ref)
        } else
            series = res[labels]!!

        series.append(timestamp, value)

        if (newSeries == false)
            postingList.append(labels, ref)
    }

    fun delete(labels: Labels, range: TimeRange) {
        val series = stripe.query(listOf(labels))[labels] ?: return

        val ref = series.ref
        tombstones.append(ref, range)
    }

    fun query(labels: Labels, range: TimeRange): List<MetricValue> {
        val result: ArrayList<MetricValue> = ArrayList()
        val refs = postingList.query(labels)
        val metrics = HashMap<SeriesRef, ArrayList<MetricValue>>()
        val series = stripe.query(refs)

        //TODO: правильная ли это точка линеаризации?
        for (ref in refs)
            metrics[ref] = series[ref]?.query(range) ?: ArrayList()

        val tombstones = tombstones.query(refs)


        for (ref in refs) {
            val metric = metrics[ref] ?: ArrayList()
            val tombstone = tombstones[ref] ?: ArrayList()

            result.addAll(mergeRef(metric, tombstone))
        }

        return result
    }

    private fun mergeRef(metrics: List<MetricValue>, tombStones: List<TimeRange>) : List<MetricValue>
    {
        val result = ArrayList<MetricValue>()

        if (metrics.isEmpty())
            return emptyList()

        if (tombStones.isEmpty()) {
            result.addAll(metrics)
            return result
        }

        var metricIndex = 0
        var tombstoneIndex = 0

        while (metricIndex < metrics.size && tombstoneIndex < tombStones.size) {
            val metricEntry = metrics[metricIndex]
            val tombstoneEntry = tombStones[tombstoneIndex]

            if (tombstoneEntry.maxTime < metricEntry.timestamp) {
                tombstoneIndex++
            } else if (metricEntry.timestamp < tombstoneEntry.minTime) {
                result.add(metricEntry)
                metricIndex++
            } else if (tombstoneEntry.overlaps(metricEntry.timestamp))
                metricIndex++
        }

        while (metricIndex < metrics.size) {

            val metricEntry = metrics[metricIndex]
            result.add(metricEntry)
            metricIndex++
        }

        return result
    }

    companion object {
        private const val StripeSize: Int = 14;
    }

}



