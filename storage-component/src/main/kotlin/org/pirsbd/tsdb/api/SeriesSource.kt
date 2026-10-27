package org.pirsbd.tsdb.api

/**
 * Источник серий. Реализуют in-memory Head и блок на диске, поэтому
 * запросу всё равно, откуда пришли данные.
 *
 * Публичный, а не internal: MemoryHead публичный, а публичный класс
 * не может наследовать internal интерфейс. Именно на этом не собирался
 * PR #7, когда SeriesRef сделали internal.
 */
interface SeriesSource : AutoCloseable {
    /** TimeRange.EMPTY, если источник пуст. */
    val range: TimeRange

    /** Серии отсортированы по Labels. */
    fun select(range: TimeRange, matchers: List<Matcher>): SeriesSet
}
