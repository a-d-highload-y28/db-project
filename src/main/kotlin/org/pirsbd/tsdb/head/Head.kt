package org.pirsbd.tsdb.head

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.SeriesSource
import org.pirsbd.tsdb.api.TimeRange

/**
 * In-memory часть БД. Реализует SeriesSource, поэтому запрос работает с
 * Head и с блоком на диске одинаково.
 */
interface Head : SeriesSource {
    fun append(labels: Labels, timestamp: Long, value: Double)
    fun delete(range: TimeRange, matchers: List<Matcher>)

    /**
     * Отрезает всё строго меньше upTo и отдаёт снимок отрезанного.
     * null, если под upTo не попал ни один сэмпл: тогда писать блок незачем.
     */
    fun truncate(upTo: Long): HeadSnapshot?
}
