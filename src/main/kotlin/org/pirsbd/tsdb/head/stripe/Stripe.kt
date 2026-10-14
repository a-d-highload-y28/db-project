package org.pirsbd.tsdb.head.stripe

import org.pirsbd.tsdb.api.Label
import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.head.memseries.MemorySeries
import org.pirsbd.tsdb.index.SeriesRef
import kotlin.concurrent.withLock

internal class Stripe(size: Int) {
    private val shards: Array<StripeCell> = Array(size, { _ -> StripeCell() })

    fun appendSeriesOrGet(series: MemorySeries, ref: SeriesRef) : MemorySeries? {
        val refIndex = getIndexByRef(ref)
        val labelHashIndex = getIndexByLabels(series.labels)
        var hashCell = shards[labelHashIndex]

        hashCell.lock.writeLock().withLock {
            val existingSeries = hashCell.labelHashMap.putIfAbsent(series.labels, series)

            if (existingSeries != null) {
                return existingSeries
            }
        }

        val refCell = shards[refIndex]

        refCell.lock.writeLock().withLock {
            refCell.refMap.putIfAbsent(ref, series)
        }

        return null
    }

    fun queryByRefs(refs: List<SeriesRef>): Map<SeriesRef, MemorySeries> {
        //TODO: Batch locks
        val accum: HashMap<SeriesRef, MemorySeries> = HashMap(refs.size)

        for (ref in refs) {
            val index = getIndexByRef(ref)
            val cell = shards[index]

            cell.lock.readLock().withLock {
                if (cell.refMap[ref] != null)
                    accum[ref] = cell.refMap[ref]!!
            }
        }

        return accum
    }

    //TODO: no sort
    fun queryByLabels(labels: List<Labels>): Map<Labels, MemorySeries> {
        val accum: HashMap<Labels, MemorySeries> = HashMap(labels.size)

        for (labelSet in labels) {
            val index = getIndexByLabels(labelSet)
            val cell = shards[index]

            cell.lock.readLock().withLock {
                if (cell.labelHashMap[labelSet] != null)
                    accum[labelSet] = cell.labelHashMap[labelSet]!! //TODO: check
            }
        }

        return accum
    }

    private fun getIndexByRef(ref: SeriesRef): Int {
        return Math.floorMod(hash(ref), shards.size)
    }

    private fun getIndexByLabels(labels: List<Label>): Int {
        return Math.floorMod(hash(labels), shards.size)  //TODO: proper hash
    }

    private fun hash(ref: SeriesRef): Int {
        return ref.value.hashCode()
    }

    private fun hash(labels: List<Label>): Int {
        return labels.hashCode()
    }
}