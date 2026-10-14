package org.pirsbd.tsdb.api.impl

import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.Querier
import org.pirsbd.tsdb.api.SeriesSet
import org.pirsbd.tsdb.api.SeriesSource
import org.pirsbd.tsdb.api.TimeRange

/**
 * Снимок источников на момент открытия. Head передаётся первым, поэтому
 * при равном таймстемпе выигрывает он.
 *
 * Снимок настоящий только для блоков: Head продолжает мутировать под
 * открытым querier. Полный снимок потребовал бы MVCC или копии данных,
 * это за пределами лабы 2.
 */
internal class DefaultQuerier(
    override val range: TimeRange,
    private val sources: List<SeriesSource>,
) : Querier {

    override fun select(matchers: List<Matcher>): SeriesSet =
        MergeSeriesSet(sources.map { it.select(range, matchers) })

    /** Источники принадлежат БД, а не querier, поэтому закрывать их нельзя. */
    override fun close() = Unit
}
