package org.pirsbd.tsdb.api.impl

import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.Querier
import org.pirsbd.tsdb.api.SeriesSet
import org.pirsbd.tsdb.api.TimeRange

/**
 * Диапазон фиксируется при открытии, а источники выбираются в момент
 * select(), атомарно относительно флаша.
 *
 * Раньше список источников снимался в querier() и потом использовался без
 * лока. Флаш между querier() и select() уносил сэмплы из Head в новый блок,
 * которого в списке нет, и запрос молча возвращал пустоту для данных, уже
 * лежащих на диске.
 *
 * Снимок по-прежнему настоящий только для блоков: Head продолжает мутировать
 * между двумя select() одного querier. Полный снимок потребовал бы MVCC.
 */
internal class DefaultQuerier(
    override val range: TimeRange,
    private val selectAtomically: (TimeRange, List<Matcher>) -> SeriesSet,
) : Querier {

    override fun select(matchers: List<Matcher>): SeriesSet = selectAtomically(range, matchers)

    /** Источники принадлежат БД, а не querier, поэтому закрывать их нельзя. */
    override fun close() = Unit
}
