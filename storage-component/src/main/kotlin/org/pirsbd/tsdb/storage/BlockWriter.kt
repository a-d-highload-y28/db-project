package org.pirsbd.tsdb.storage

import org.pirsbd.tsdb.chunks.ChunkWriter
import org.pirsbd.tsdb.index.IndexWriter

interface BlockWriter : AutoCloseable {
    val chunks: ChunkWriter
    val index: IndexWriter
    val tombstones: TombstoneWriter
    fun finish(meta: BlockMeta)
}

interface Storage : AutoCloseable {
    fun blocks(): List<Block>
    fun addBlock(block: Block)
    fun removeBlock(ulid: String)
    fun newBlockWriter(meta: BlockMeta): BlockWriter
}