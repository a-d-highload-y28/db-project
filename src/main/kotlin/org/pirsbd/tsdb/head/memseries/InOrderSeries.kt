package org.pirsbd.tsdb.head.memseries

import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.common.PeekingIterator

interface InOrderSeries {
    fun insert(timestamp: Long, value: Double)

    /** Индекс сэмпла с этим таймстемпом, или отрицательное число, если его нет. */
    fun indexOf(timestamp: Long): Int

    /** Перезаписывает сэмпл по индексу. Используется при дубликате таймстемпа. */
    fun replaceAt(index: Int, timestamp: Long, value: Double)

    fun firstTimestamp(): Long?

    fun lastTimestamp(): Long?

    fun iterator(): PeekingIterator<Sample>

    fun count(): Int
}
