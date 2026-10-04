package org.pirsbd.tsdb.api.impl

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.Series
import org.pirsbd.tsdb.api.SeriesSet

internal class MapSeriesSet(private val series: Map<Labels, List<Sample>>) : SeriesSet {
    private var iterator = series.iterator()
    private var current: Series? = null

    override fun next(): Boolean {
        if (iterator.hasNext()) {
            val next = iterator.next()
            current = LabelsSeries(next.key, next.value)
            return true
        }

        current = null
        return false
    }

    override fun at(): Series {
        if (current == null)
            throw NoSuchElementException()

        return current!!
    }

    override fun close() {
        //noop
    }
}