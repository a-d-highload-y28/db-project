package org.pirsbd.tsdb.head

import java.util.concurrent.atomic.AtomicLong

internal class LongSequence(val start: Long, val step: Long) {
    private val counter: AtomicLong = AtomicLong(start)

    fun getId(): Long {
        return counter.getAndAdd(step)
    }
}