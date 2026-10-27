package org.pirsbd.tsdb.head

import org.pirsbd.tsdb.api.Label
import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.index.SeriesRef
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/** Обратный индекс в памяти. Поддерживается только оператор '='. */
internal class PostingList {
    private val lock: ReentrantReadWriteLock = ReentrantReadWriteLock()
    private val labelRefMap: HashMap<Label, ArrayList<SeriesRef>> = HashMap()

    /**
     * Отсортированный список всех серий. Аналог allPostings в индексе на диске:
     * даёт путь для пустого списка матчеров, не сканируя всё подряд.
     */
    private val allRefs: ArrayList<SeriesRef> = ArrayList()

    fun append(labels: Labels, ref: SeriesRef) {
        lock.writeLock().withLock {
            insertSorted(allRefs, ref)
            for (label in labels) {
                insertSorted(labelRefMap.getOrPut(label) { ArrayList() }, ref)
            }
        }
    }

    fun query(matchers: List<Matcher>): List<SeriesRef> {
        lock.readLock().withLock {
            if (matchers.isEmpty()) return ArrayList(allRefs)

            val lists = ArrayList<List<SeriesRef>>(matchers.size)
            for (label in toLabels(matchers)) {
                val list = labelRefMap[label] ?: return emptyList()
                lists.add(list)
            }

            val shortest = lists.minBy { it.size }
            val result = ArrayList<SeriesRef>()
            for (ref in shortest) {
                val inAll = lists.all { list ->
                    list === shortest || list.binarySearch { x -> x.value.compareTo(ref.value) } >= 0
                }
                if (inAll) result.add(ref)
            }
            return result
        }
    }

    /** Убирает серию из индекса целиком. Вызывается, когда Head её забывает. */
    fun remove(labels: Labels, ref: SeriesRef) {
        lock.writeLock().withLock {
            removeSorted(allRefs, ref)
            for (label in labels) {
                val refs = labelRefMap[label] ?: continue
                removeSorted(refs, ref)
                if (refs.isEmpty()) labelRefMap.remove(label)
            }
        }
    }

    private fun removeSorted(list: ArrayList<SeriesRef>, ref: SeriesRef) {
        val index = list.binarySearch { x -> x.value.compareTo(ref.value) }
        if (index >= 0) list.removeAt(index)
    }

    private fun insertSorted(list: ArrayList<SeriesRef>, ref: SeriesRef) {
        val index = list.binarySearch { x -> x.value.compareTo(ref.value) }
        if (index < 0) list.add(-(index + 1), ref)
    }

    private companion object {
        fun toLabels(matchers: List<Matcher>): Labels =
            Labels(matchers.map { it as Matcher.Equals }.map { Label(it.name, it.value) })
    }
}
