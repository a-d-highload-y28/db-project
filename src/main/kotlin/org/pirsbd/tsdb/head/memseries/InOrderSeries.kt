package org.pirsbd.tsdb.head.memseries

import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.common.PeekingIterator

interface InOrderSeries {
    fun insert(timestamp: Long, value: Double)

    /** Перезаписывает последний сэмпл. Вызывается, когда таймстемп совпал. */
    fun replaceLast(timestamp: Long, value: Double)

    fun iterator(): PeekingIterator<Sample>

    fun count(): Int
}
