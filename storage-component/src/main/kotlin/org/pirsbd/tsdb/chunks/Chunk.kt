package org.pirsbd.tsdb.chunks

import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.TimeRange

enum class ChunkEncoding(val id: Byte) {
    RAW(0), XOR(1);

    companion object {
        fun fromId(id: Byte): ChunkEncoding = entries.first { it.id == id }
    }
}

@JvmInline
value class ChunkRef(val value: Long)

interface ChunkIterator {
    fun next(): Boolean
    fun at(): Sample
    fun seek(timestamp: Long): Boolean
}

interface Chunk {
    val encoding: ChunkEncoding
    val range: TimeRange
    val numSamples: Int
    fun iterator(): ChunkIterator
}
