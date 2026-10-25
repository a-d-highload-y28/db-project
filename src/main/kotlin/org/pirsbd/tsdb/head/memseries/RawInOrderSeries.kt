package org.pirsbd.tsdb.head.memseries

import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.common.PeekingIterator
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RawInOrderSeries(private var capacity: Int = 16) : InOrderSeries {
    private var buffer: ByteBuffer = ByteBuffer.allocate(Int.SIZE_BYTES + (Long.SIZE_BYTES + Double.SIZE_BYTES) * capacity)
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

    /** Буфер отсортирован по таймстемпу, поэтому бинарный поиск корректен. */
    override fun indexOf(timestamp: Long): Int {
        var low = 0
        var high = count() - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val at = timestampAt(mid)
            when {
                at < timestamp -> low = mid + 1
                at > timestamp -> high = mid - 1
                else -> return mid
            }
        }
        return -(low + 1)
    }

    override fun replaceAt(index: Int, timestamp: Long, value: Double) {
        require(index in 0 until count()) { "index out of range: $index, count ${count()}" }
        val offset = offsetOf(index)
        buffer.putLong(offset, timestamp)
        buffer.putDouble(offset + Long.SIZE_BYTES, value)
    }

    override fun firstTimestamp(): Long? = if (count() == 0) null else timestampAt(0)

    override fun lastTimestamp(): Long? = if (count() == 0) null else timestampAt(count() - 1)

    private fun offsetOf(index: Int): Int =
        Int.SIZE_BYTES + index * (Long.SIZE_BYTES + Double.SIZE_BYTES)

    private fun timestampAt(index: Int): Long = buffer.getLong(offsetOf(index))

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

