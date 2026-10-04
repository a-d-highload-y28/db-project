package org.pirsbd.tsdb.head

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.head.memseries.MemorySeries
import org.pirsbd.tsdb.head.stripe.Stripe
import org.pirsbd.tsdb.index.SeriesRef

internal class MemoryChunk {
    private val sequence: LongSequence = LongSequence(1, 1)
    private val stripe: Stripe = Stripe(StripeSize)
    private val postingList: PostingList = PostingList()
    private val tombstones: Tombstones = Tombstones()

    fun append(labels: Labels, timestamp: Long, value: Double) {
        var ref = SeriesRef(sequence.getId())

        val res = stripe.queryByLabels(listOf(labels))
        var series: MemorySeries

        val isExistingSeries = res.containsKey(labels)

        if (!isExistingSeries) {
            series = MemorySeries(ref, labels)
            val existingSeries = stripe.appendSeriesOrGet(series, ref)

            if (existingSeries != null) {
                series = existingSeries
                ref = series.ref
            }
        } else
            series = res[labels]!!

        series.append(timestamp, value)

        if (!isExistingSeries)
            postingList.append(labels, ref)
    }

    fun delete(matchers: List<Matcher>, range: TimeRange) {
        val refs = postingList.query(matchers)

        for (ref in refs)
            tombstones.append(ref, range)
    }

    fun query(labels: List<Matcher>, range: TimeRange): Map<Labels, List<Sample>> {
        val result: HashMap<Labels, List<Sample>> = HashMap()
        val metrics = HashMap<SeriesRef, ArrayList<Sample>>()
        val refToLabels = HashMap<SeriesRef, Labels>()

        val refs = postingList.query(labels)
        val series = stripe.queryByRefs(refs)


        for (ref in refs) {
            val series = series[ref]

            if (series == null) {
                metrics[ref] = arrayListOf()
                continue
            }

            refToLabels[ref] = series.labels
            metrics[ref] = series.query(range)
        }
        val tombstones = tombstones.query(refs)

        for (ref in refs) {
            val labels = refToLabels[ref] ?: continue

            val metric = metrics[ref] ?: ArrayList()
            val tombstone = tombstones[ref] ?: ArrayList()

            result[labels] = mergeRef(metric, tombstone)
        }

        return result
    }

    private fun mergeRef(metrics: List<Sample>, tombStones: List<TimeRange>): List<Sample> {
        val result = ArrayList<Sample>()

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
            } else if (tombstoneEntry.contains(metricEntry.timestamp))
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