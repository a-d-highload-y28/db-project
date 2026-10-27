package org.pirsbd.tsdb.common

interface PeekingIterator<T> : Iterator<T> {
    fun peek(): T
}