package org.pirsbd.tsdb.index

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.chunks.ChunkRef
import org.pirsbd.tsdb.api.TimeRange

interface IndexWriter : AutoCloseable {
    fun addSymbol(symbol: String): Int
    fun addSeries(labels: Labels, chunks: List<Pair<ChunkRef, TimeRange>>): SeriesRef
    fun finish()
}
