package org.pirsbd.tsdb.head

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.TimeRange

/** Серия снимка. Сэмплы отсортированы по таймстемпу, дублей нет. */
data class SnapshotSeries(val labels: Labels, val samples: List<Sample>)

/**
 * Маркер удаления в терминах Labels, а не SeriesRef: оффсеты серий внутри
 * блока существуют только после того, как индекс записан, поэтому ref из
 * Head писателю блока бесполезен.
 */
data class SnapshotTombstone(val labels: Labels, val range: TimeRange)

/**
 * Снимок отрезанной части Head.
 *
 * Head отдаёт данные, а писать их в блок это дело BlockWriter. Если бы
 * truncate возвращал готовый Block, Head понадобились бы папка, ULID и
 * писатель, то есть знание про диск уехало бы внутрь in-memory компонента.
 */
interface HeadSnapshot {
    val range: TimeRange
    val numSeries: Int
    val numSamples: Long

    /** Серии в порядке Labels. */
    fun series(): List<SnapshotSeries>

    fun tombstones(): List<SnapshotTombstone>
}

internal class MaterializedSnapshot(
    private val series: List<SnapshotSeries>,
    private val tombstones: List<SnapshotTombstone>,
) : HeadSnapshot {
    init {
        require(series.isNotEmpty()) { "snapshot must not be empty; return null instead" }
    }

    override val range: TimeRange = TimeRange(
        series.minOf { it.samples.first().timestamp },
        series.maxOf { it.samples.last().timestamp },
    )

    override val numSeries: Int = series.size

    override val numSamples: Long = series.sumOf { it.samples.size.toLong() }

    override fun series(): List<SnapshotSeries> = series

    override fun tombstones(): List<SnapshotTombstone> = tombstones
}
