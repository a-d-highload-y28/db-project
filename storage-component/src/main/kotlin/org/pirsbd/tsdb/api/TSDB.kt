package org.pirsbd.tsdb.api

data class WriteRequest(val labels: Labels, val sample: Sample)

interface Appender : AutoCloseable {
    fun add(labels: Labels, timestamp: Long, value: Double)
    fun commit()
    fun rollback()
}

interface Querier : AutoCloseable {
    val range: TimeRange
    fun select(matchers: List<Matcher>): SeriesSet
}

interface TSDB : AutoCloseable {
    fun appender(): Appender
    fun querier(range: TimeRange): Querier
    fun delete(range: TimeRange, matchers: List<Matcher>)

    /**
     * Сбрасывает Head в блок на диске.
     *
     * Публичный, потому что иначе «ручной флаш» из спеки недоступен снаружи,
     * а тестам и демо он нужен, чтобы флаш был детерминированным.
     */
    fun flush()
}
