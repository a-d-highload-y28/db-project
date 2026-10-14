package org.pirsbd.tsdb.head

import org.pirsbd.tsdb.api.Label
import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.index.SeriesRef
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

//Only '=' operation is supported
internal class PostingList {
    private val lock: ReentrantReadWriteLock = ReentrantReadWriteLock()
    private val labelRefMap: HashMap<Label, ArrayList<SeriesRef>> = HashMap()

    fun append(labels: Labels, ref: SeriesRef) {
        lock.writeLock().withLock {
            for (label in labels) {
                val seriesRef = labelRefMap.putIfAbsent(label, arrayListOf(ref))

                if (seriesRef != null) {
                    var index = seriesRef.binarySearch { x -> x.value.compareTo(ref.value) }

                    if (index < 0) {
                        index = -(index + 1)
                        seriesRef.add(index, ref)
                    }
                }
            }
        }
    }

    fun query(matcher: List<Matcher>): List<SeriesRef> {
        val series: ArrayList<List<SeriesRef>> = ArrayList()
        var shortestListIndex = 0
        var shortedListEntryCount = Int.MAX_VALUE
        val result: ArrayList<SeriesRef> = ArrayList()

        val labels = toLabels(matcher)

        lock.readLock().withLock {
            for ((index, label) in labels.withIndex()) {
                if (labelRefMap.containsKey(label)) {
                    val list = labelRefMap.getValue(label)
                    series.add(list)

                    if (list.size < shortedListEntryCount) {
                        shortestListIndex = index;
                        shortedListEntryCount = list.size
                    }
                } else {
                    return emptyList()
                }
            }

            //Equals matcher-specific
            val shortestRefList = series[shortestListIndex]
            for (ref in shortestRefList) {
                var count = 1
                for ((index, refList) in series.withIndex()) {
                    if (index == shortestListIndex)
                        continue

                    if (refList.binarySearch { x -> x.value.compareTo(ref.value) } >= 0)
                        count++
                }

                if (count == series.size)
                    result.add(ref)
            }

            return result
        }
    }

    companion object {
        private fun toLabels(matchers: List<Matcher>): Labels {
            if (matchers.isEmpty()) return Labels(emptyList())

            //Забиваемся на то, что разных типов в одном списке мэтчеров не будет
            return when (val type = matchers.first()) {
                is Matcher.Equals -> Labels(matchers.map { x -> x as Matcher.Equals }.map { matcher -> Label(matcher.name, matcher.value) })
            }
        }
    }
}