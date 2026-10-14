package org.pirsbd.tsdb.api.impl

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Series
import org.pirsbd.tsdb.api.SeriesIterator
import org.pirsbd.tsdb.api.SeriesSet

/**
 * k-way merge источников по Labels. Источники обязаны отдавать серии
 * отсортированными, это контракт SeriesSource.
 *
 * При совпадении Labels приоритет у того, кто раньше в списке: Head
 * передаётся первым.
 */
internal class MergeSeriesSet(sources: List<SeriesSet>) : SeriesSet {
    private val live = ArrayList<SeriesSet>(sources.size)
    private val pending = ArrayList<SeriesSet>()
    private var current: Series? = null

    init {
        for (source in sources) {
            if (source.next()) live.add(source) else source.close()
        }
    }

    override fun next(): Boolean {
        advancePending()

        if (live.isEmpty()) {
            current = null
            return false
        }

        var min = live[0].at().labels
        for (source in live) {
            val labels = source.at().labels
            if (LabelsOrder.compare(labels, min) < 0) min = labels
        }

        val iterators = ArrayList<SeriesIterator>()
        for (source in live) {
            if (LabelsOrder.compare(source.at().labels, min) == 0) {
                iterators.add(source.at().iterator())
                pending.add(source)
            }
        }

        current = MergedSeries(min, iterators)
        return true
    }

    override fun at(): Series = current ?: throw NoSuchElementException()

    override fun close() {
        live.forEach { it.close() }
        live.clear()
        pending.clear()
    }

    /**
     * Источники двигаются не сразу, а в начале следующего next(). Иначе
     * at() отдал бы серию, которую источник уже затёр: контракт SeriesSet
     * says at() действителен до следующего next().
     */
    private fun advancePending() {
        for (source in pending) {
            if (!source.next()) {
                live.remove(source)
                source.close()
            }
        }
        pending.clear()
    }

    private class MergedSeries(
        override val labels: Labels,
        private val iterators: List<SeriesIterator>,
    ) : Series {
        override fun iterator(): SeriesIterator = MergeSeriesIterator(iterators)
    }
}
