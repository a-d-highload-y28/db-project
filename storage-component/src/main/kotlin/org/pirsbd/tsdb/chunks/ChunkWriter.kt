package org.pirsbd.tsdb.chunks

import org.pirsbd.tsdb.api.Sample

interface ChunkBuilder {
    val encoding: ChunkEncoding
    val numSamples: Int
    fun append(sample: Sample)
    fun build(): Chunk
}

interface ChunkWriter : AutoCloseable {
    fun write(chunk: Chunk): ChunkRef
    fun flush()
}
