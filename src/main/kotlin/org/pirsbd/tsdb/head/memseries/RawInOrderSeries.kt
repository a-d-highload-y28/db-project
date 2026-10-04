package org.pirsbd.tsdb.head.memseries

import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.common.PeekingIterator
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RawInOrderSeries(private var capacity: Int = 16) : InOrderSeries {
    private var buffer: ByteBuffer = ByteBuffer.allocate(Int.SIZE_BYTES + (Long.SIZE_BYTES + Double.SIZE_BYTES) * capacity.toInt())
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(0)

    override fun insert(timestamp: Long, value: Double) {
        var count = buffer.getInt(0)

        if (capacity <= count)
            extend(capacity * 2)

        count += 1

        buffer.putLong(timestamp)
        buffer.putDouble(value)

        buffer.putInt(0, count)
    }

    override fun iterator(): PeekingIterator<Sample> = RawInOrderIterator(buffer)

    override fun count(): Int {
        return buffer.getInt(0)
    }

    private fun extend(newCapacity: Int) {
        val newBuffer = ByteBuffer.allocate(Int.SIZE_BYTES + (Long.SIZE_BYTES + Double.SIZE_BYTES) * newCapacity)
            .order(ByteOrder.BIG_ENDIAN);

        buffer.flip()
        newBuffer.put(buffer)

        buffer = newBuffer
        capacity = newCapacity
    }
}

