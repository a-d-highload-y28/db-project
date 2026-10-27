package org.pirsbd.tsdb.api.impl

import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.SeriesIterator

/**
 * Сливает сэмплы нескольких итераторов по возрастанию таймстемпа.
 *
 * При равных таймстемпах выигрывает итератор, идущий раньше в списке.
 * Head передаётся первым, поэтому свежее значение из памяти перебивает
 * то, что уже лежит в блоке.
 */
internal class MergeSeriesIterator(iterators: List<SeriesIterator>) : SeriesIterator {
    private val live = ArrayList<SeriesIterator>(iterators.size)
    private var current: Sample? = null

    init {
        for (iterator in iterators) {
            if (iterator.next()) live.add(iterator) else iterator.close()
        }
    }

    override fun next(): Boolean {
        if (live.isEmpty()) {
            current = null
            return false
        }

        var best = 0
        for (i in live.indices) {
            if (live[i].at().timestamp < live[best].at().timestamp) best = i
        }
        val timestamp = live[best].at().timestamp
        current = live[best].at()

        // Продвинуть все источники с этим таймстемпом, иначе дубликат вылезет снова.
        var i = 0
        while (i < live.size) {
            if (live[i].at().timestamp == timestamp && !live[i].next()) {
                live[i].close()
                live.removeAt(i)
            } else {
                i += 1
            }
        }
        return true
    }

    override fun at(): Sample = current ?: throw NoSuchElementException()

    override fun close() {
        live.forEach { it.close() }
        live.clear()
    }
}
