package org.pirsbd.tsdb.api

data class WriteRequest(val labels: Labels, val sample: Sample)

interface Appender : AutoCloseable {
    fun add(labels: Labels, timestamp: Long, value: Double)
    fun commit()
    fun rollback()
}

interface Querier : AutoCloseable {
    fun select(range: TimeRange, matchers: List<Matcher>): SeriesSet
}

interface TSDB : AutoCloseable {
    fun appender(): Appender
    fun querier(range: TimeRange): Querier
    fun delete(range: TimeRange, matchers: List<Matcher>)
}
