package org.pirsbd.tsdb.api.impl

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.Series
import org.pirsbd.tsdb.api.SeriesIterator

internal class LabelsSeries(val labelSet: Labels, samples: List<Sample>) : Series {
    private val iterator: ListSeriesSampleIterator = ListSeriesSampleIterator(samples)

    override val labels: Labels
        get() = labelSet

    override fun iterator(): SeriesIterator {
        return iterator
    }
}