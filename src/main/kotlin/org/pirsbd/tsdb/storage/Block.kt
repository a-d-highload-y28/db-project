package org.pirsbd.tsdb.storage

import org.pirsbd.tsdb.chunks.ChunkReader
import org.pirsbd.tsdb.index.IndexReader

interface Block : AutoCloseable {
    val meta: BlockMeta
    fun index(): IndexReader
    fun chunks(): ChunkReader
    fun tombstones(): TombstoneReader
}
