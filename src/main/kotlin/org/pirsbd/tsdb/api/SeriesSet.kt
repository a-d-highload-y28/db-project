package org.pirsbd.tsdb.api

interface SeriesIterator : AutoCloseable {
    fun next(): Boolean
    fun at(): Sample
}

interface Series {
    val labels: Labels
    fun iterator(): SeriesIterator
}

interface SeriesSet : AutoCloseable {
    fun next(): Boolean
    fun at(): Series
}
