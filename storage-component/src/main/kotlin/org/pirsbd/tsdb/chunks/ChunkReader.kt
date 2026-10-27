package org.pirsbd.tsdb.chunks

interface ChunkReader : AutoCloseable {
    fun read(ref: ChunkRef): Chunk
}
