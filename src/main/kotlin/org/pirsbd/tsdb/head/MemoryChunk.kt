package org.pirsbd.tsdb.head

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.api.impl.LabelsOrder
import org.pirsbd.tsdb.head.memseries.MemorySeries
import org.pirsbd.tsdb.head.stripe.Stripe
import org.pirsbd.tsdb.index.SeriesRef
import java.util.TreeMap

internal class MemoryChunk(stripeSize: Int) {
    private val sequence: LongSequence = LongSequence(1, 1)
    private val stripe: Stripe = Stripe(stripeSize)
    private val postingList: PostingList = PostingList()
    private val tombstones: Tombstones = Tombstones()

    /**
     * Id из последовательности берётся только когда серия действительно
     * новая. Раньше он брался на каждый сэмпл и раздувал пространство
     * SeriesRef на миллионы значений для одной серии.
     */
    fun append(labels: Labels, timestamp: Long, value: Double) {
        val existing = stripe.queryByLabels(listOf(labels))[labels]
        if (existing != null) {
            existing.append(timestamp, value)
            return
        }

        val ref = SeriesRef(sequence.getId())
        val series = MemorySeries(ref, labels)
        val raced = stripe.appendSeriesOrGet(series, ref)
        if (raced != null) {
            raced.append(timestamp, value)
            return
        }

        series.append(timestamp, value)
        postingList.append(labels, ref)
    }

    fun seriesCount(): Int = postingList.query(emptyList()).size

    fun delete(matchers: List<Matcher>, range: TimeRange) {
        for (ref in postingList.query(matchers)) {
            tombstones.append(ref, range)
        }
    }

    /** Результат отсортирован по Labels: это часть контракта SeriesSource. */
    fun query(matchers: List<Matcher>, range: TimeRange): TreeMap<Labels, List<Sample>> {
        val result = TreeMap<Labels, List<Sample>>(LabelsOrder.COMPARATOR)

        val refs = postingList.query(matchers)
        val found = stripe.queryByRefs(refs)
        val deleted = tombstones.query(refs)

        for (ref in refs) {
            val series = found[ref] ?: continue
            val samples = mergeRef(series.query(range), deleted[ref] ?: emptyList())
            // Серия без сэмплов в диапазоне не попадает в результат вовсе.
            // Иначе запрос вернул бы пустую серию, а не её отсутствие, и
            // серия, целиком накрытая tombstone, продолжала бы светиться.
            if (samples.isNotEmpty()) result[series.labels] = samples
        }
        return result
    }

    /**
     * Отдаёт серии с сэмплами строго меньше upTo и убирает их из памяти.
     * Серии в порядке Labels: это контракт HeadSnapshot.
     */
    fun drain(upTo: Long): List<SnapshotSeries> {
        val refs = postingList.query(emptyList())
        val found = stripe.queryByRefs(refs)

        val result = ArrayList<SnapshotSeries>(refs.size)
        val forgotten = ArrayList<MemorySeries>()

        for (ref in refs) {
            val series = found[ref] ?: continue
            val drained = series.drainBelow(upTo)
            if (drained.isNotEmpty()) result.add(SnapshotSeries(series.labels, drained))
            if (series.isEmptyNow()) forgotten.add(series)
        }

        // Серию без сэмплов Head забывает целиком. Иначе её запись осталась бы
        // в stripe и в postings навсегда, а drain, drainTombstones и
        // currentRange обходят их все: стоимость флаша росла бы вместе с
        // числом серий, которые БД когда-либо видела, и не падала обратно.
        for (series in forgotten) {
            stripe.remove(series.labels, series.ref)
            postingList.remove(series.labels, series.ref)
            tombstones.remove(series.ref)
        }

        result.sortWith { a, b -> LabelsOrder.compare(a.labels, b.labels) }
        return result
    }

    /**
     * Маркеры удаления в терминах Labels, для писателя блока.
     *
     * Head их не теряет: если у серии остались свежие сэмплы, маркер обязан
     * продолжать действовать и в памяти. Поэтому это копия, а не изъятие.
     * Один и тот же маркер может попасть в два блока, это безвредно: каждый
     * блок фильтрует только свои серии.
     */
    fun drainTombstones(): List<SnapshotTombstone> {
        val refs = postingList.query(emptyList())
        val found = stripe.queryByRefs(refs)
        val byRef = tombstones.query(refs)

        val result = ArrayList<SnapshotTombstone>()
        for (ref in refs) {
            val series = found[ref] ?: continue
            for (range in byRef[ref] ?: emptyList()) {
                result.add(SnapshotTombstone(series.labels, range))
            }
        }
        return result
    }

    /**
     * Диапазон того, что осталось в памяти. null, если сэмплов не осталось.
     * Обходит живые серии, а не все их сэмплы: каждая отдаёт свои границы за O(1).
     */
    fun currentRange(): TimeRange? {
        val refs = postingList.query(emptyList())
        val found = stripe.queryByRefs(refs)

        var min = Long.MAX_VALUE
        var max = Long.MIN_VALUE
        for (ref in refs) {
            val range = found[ref]?.currentRange() ?: continue
            if (range.minTime < min) min = range.minTime
            if (range.maxTime > max) max = range.maxTime
        }
        return if (min > max) null else TimeRange(min, max)
    }

    private fun mergeRef(samples: List<Sample>, tombstones: List<TimeRange>): List<Sample> {
        if (samples.isEmpty()) return emptyList()
        if (tombstones.isEmpty()) return samples

        val result = ArrayList<Sample>(samples.size)
        var sampleIndex = 0
        var tombstoneIndex = 0

        while (sampleIndex < samples.size && tombstoneIndex < tombstones.size) {
            val sample = samples[sampleIndex]
            val tombstone = tombstones[tombstoneIndex]

            when {
                tombstone.maxTime < sample.timestamp -> tombstoneIndex++
                sample.timestamp < tombstone.minTime -> {
                    result.add(sample)
                    sampleIndex++
                }
                else -> sampleIndex++
            }
        }

        while (sampleIndex < samples.size) {
            result.add(samples[sampleIndex])
            sampleIndex++
        }
        return result
    }
}
