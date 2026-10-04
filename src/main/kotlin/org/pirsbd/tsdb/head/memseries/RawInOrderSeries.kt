package org.pirsbd.tsdb.head.memseries

import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.common.PeekingIterator
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RawInOrderSeries : InOrderSeries {
    private val buffer: ByteBuffer = ByteBuffer.allocate(Long.SIZE_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .putLong(0)

    override fun insert(timestamp: Long, value: Double) {
        var count = buffer.getLong(0)
        count += 1

        buffer.putLong(timestamp)
        buffer.putDouble(value)

        buffer.putLong(0, count)
    }

    override fun iterator(): PeekingIterator<Sample> = RawInOrderIterator(buffer)

    override fun count(): Long {
        return buffer.getLong(0)
    }
}

