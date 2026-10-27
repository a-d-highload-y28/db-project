package org.pirsbd.tsdb.storage

import org.pirsbd.tsdb.api.TimeRange

data class BlockStats(val numSamples: Long, val numSeries: Long, val numChunks: Long)

data class Compaction(val level: Int, val sources: List<String>)

data class BlockMeta(
    val ulid: String,
    val range: TimeRange,
    val stats: BlockStats,
    val compaction: Compaction,
    val version: Int,
)
