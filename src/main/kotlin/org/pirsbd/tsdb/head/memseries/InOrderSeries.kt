package org.pirsbd.tsdb.head.memseries

import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.common.PeekingIterator

interface InOrderSeries {
    fun insert(timestamp: Long, value: Double)

    fun iterator(): PeekingIterator<Sample>

    fun count(): Int
}