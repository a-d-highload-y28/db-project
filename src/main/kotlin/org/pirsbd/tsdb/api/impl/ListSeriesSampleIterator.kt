package org.pirsbd.tsdb.api.impl

import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.SeriesIterator

internal class ListSeriesSampleIterator(private val list: List<Sample>) : SeriesIterator {
    private var index = 0

    override fun next(): Boolean {
        if (index >= list.size) return false

        ++index
        return true
    }

    override fun at(): Sample {
        return list[index]
    }

    override fun close() {
        //noop
    }
}