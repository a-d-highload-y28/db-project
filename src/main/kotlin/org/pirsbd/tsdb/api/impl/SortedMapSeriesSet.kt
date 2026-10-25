package org.pirsbd.tsdb.api.impl

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.Series
import org.pirsbd.tsdb.api.SeriesSet
import java.util.SortedMap

/** Параметр SortedMap, а не Map: порядок серий это часть контракта SeriesSource. */
internal class SortedMapSeriesSet(series: SortedMap<Labels, List<Sample>>) : SeriesSet {
    private val iterator = series.entries.iterator()
    private var current: Series? = null

    override fun next(): Boolean {
        if (!iterator.hasNext()) {
            current = null
            return false
        }
        val entry = iterator.next()
        current = LabelsSeries(entry.key, entry.value)
        return true
    }

    override fun at(): Series = current ?: throw NoSuchElementException()

    override fun close() = Unit
}
