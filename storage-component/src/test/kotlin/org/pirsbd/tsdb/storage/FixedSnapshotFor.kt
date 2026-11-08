package org.pirsbd.tsdb.storage

import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.head.HeadSnapshot
import org.pirsbd.tsdb.head.SnapshotSeries
import org.pirsbd.tsdb.head.SnapshotTombstone

/** Снимок из заранее заданных серий, чтобы тестировать запись блока без Head. */
class FixedSnapshotFor(
    private val series: List<SnapshotSeries>,
    private val tombstones: List<SnapshotTombstone> = emptyList(),
) : HeadSnapshot {
    override val range: TimeRange
        get() {
            val all = series.flatMap { it.samples }
            return TimeRange(all.minOf { it.timestamp }, all.maxOf { it.timestamp })
        }

    override val numSeries: Int get() = series.size

    override val numSamples: Long get() = series.sumOf { it.samples.size.toLong() }

    override fun series(): List<SnapshotSeries> = series

    override fun tombstones(): List<SnapshotTombstone> = tombstones
}
