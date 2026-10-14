package org.pirsbd.tsdb.merge

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.SeriesSet
import org.pirsbd.tsdb.api.SeriesSource
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.api.impl.LabelsOrder
import org.pirsbd.tsdb.api.impl.SortedMapSeriesSet
import java.util.TreeMap

/** Источник из заранее заданных серий. Считает, сколько раз его опрашивали. */
class FakeSeriesSource(series: Map<Labels, List<Sample>>) : SeriesSource {
    var selectCalls: Int = 0
        private set

    private val data = TreeMap<Labels, List<Sample>>(LabelsOrder.COMPARATOR).also { it.putAll(series) }

    override val range: TimeRange
        get() {
            val all = data.values.flatten()
            if (all.isEmpty()) return TimeRange.EMPTY
            return TimeRange(all.minOf { it.timestamp }, all.maxOf { it.timestamp })
        }

    override fun select(range: TimeRange, matchers: List<Matcher>): SeriesSet {
        selectCalls += 1
        val filtered = TreeMap<Labels, List<Sample>>(LabelsOrder.COMPARATOR)
        for ((labels, samples) in data) {
            if (matchers.any { m -> labels.get(m.name) != (m as Matcher.Equals).value }) continue
            val inRange = samples.filter { range.contains(it.timestamp) }
            if (inRange.isNotEmpty()) filtered[labels] = inRange
        }
        return SortedMapSeriesSet(filtered)
    }

    override fun close() = Unit
}
