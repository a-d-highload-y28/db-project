package org.pirsbd.tsdb.head.memseries

import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.common.PeekingIterator
import java.nio.ByteBuffer

class RawInOrderIterator(private val buffer: ByteBuffer) : PeekingIterator<Sample> {
    var index: Int = 0
    var position: Int = Int.SIZE_BYTES


    override fun next(): Sample {
        if (!hasNext())
            throw NoSuchElementException()

        val timestamp = buffer.getLong(position)
        position += Long.SIZE_BYTES

        val value = buffer.getDouble(position)
        position += Double.SIZE_BYTES


        index += 1

        return Sample(timestamp, value)
    }

    override fun hasNext(): Boolean {
        return index < getCount()
    }

    override fun peek(): Sample {
        if (!hasNext())
            throw NoSuchElementException()

        val timestamp = buffer.getLong(position)
        val value = buffer.getDouble(position + Long.SIZE_BYTES)

        return Sample(timestamp, value)
    }

    private fun getCount(): Int {
        return buffer.getInt(0)
    }
}