package org.pirsbd.tsdb.api.impl

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.Series
import org.pirsbd.tsdb.api.SeriesIterator

internal class LabelsSeries(
    override val labels: Labels,
    private val samples: List<Sample>,
) : Series {
    /** Новый итератор на каждый вызов: Series можно обойти несколько раз. */
    override fun iterator(): SeriesIterator = ListSeriesSampleIterator(samples)
}
