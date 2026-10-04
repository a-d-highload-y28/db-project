package org.pirsbd.tsdb.api.impl

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.Series
import org.pirsbd.tsdb.api.SeriesSet

internal class MapSeriesSet(private val series: Map<Labels, List<Sample>>) : SeriesSet {
    private var iterator = series.iterator()

    override fun next(): Boolean {
        return iterator.hasNext()
    }

    override fun at(): Series {
        val next = iterator.next()
        return LabelsSeries(next.key, next.value)
    }

    override fun close() {
        //noop
    }
}