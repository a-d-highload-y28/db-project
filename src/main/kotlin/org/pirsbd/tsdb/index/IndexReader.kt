package org.pirsbd.tsdb.index

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.chunks.ChunkRef
import org.pirsbd.tsdb.api.TimeRange

data class SeriesChunkMeta(val ref: ChunkRef, val range: TimeRange)

data class SeriesEntry(val labels: Labels, val chunks: List<SeriesChunkMeta>)

interface IndexReader : AutoCloseable {
    fun symbols(): SymbolTable
    fun series(ref: SeriesRef): SeriesEntry
    fun postings(name: String, value: String): Postings
    fun labelNames(): List<String>
    fun labelValues(name: String): List<String>
}
