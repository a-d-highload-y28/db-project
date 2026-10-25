package org.pirsbd.tsdb.storage

import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.SeriesSet
import org.pirsbd.tsdb.api.SeriesSource
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.api.impl.LabelsOrder
import org.pirsbd.tsdb.api.impl.SortedMapSeriesSet
import org.pirsbd.tsdb.storage.codec.BlockMetaJson
import org.pirsbd.tsdb.storage.codec.ChunkFileCodec
import org.pirsbd.tsdb.storage.codec.IndexContents
import org.pirsbd.tsdb.storage.codec.IndexFileCodec
import org.pirsbd.tsdb.storage.codec.Tombstone
import org.pirsbd.tsdb.storage.codec.TombstoneFileCodec
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.util.TreeMap

/**
 * Блок на диске как источник серий.
 *
 * Сознательно не реализует интерфейс Block из лабы 1: тот требует три
 * отдельных ридера (index, chunks, tombstones), а запросу нужен один
 * select. Ридеры остались бы пустой прослойкой. Block, IndexReader,
 * ChunkReader и TombstoneReader пригодятся в лабе 3 для компакции.
 *
 * Все файлы блока иммутабельны, кроме tombstones.
 */
internal class DiskBlock(private val dir: Path) : SeriesSource {

    val meta: BlockMeta = BlockMetaJson.decode(Files.readString(dir.resolve("meta.json")))

    private val indexBytes: ByteArray = Files.readAllBytes(dir.resolve("index"))
    private val chunkBytes: ByteArray = Files.readAllBytes(dir.resolve("chunks/000001"))
    private val contents: IndexContents = IndexFileCodec.read(indexBytes)

    /**
     * Лок обязателен: @Volatile делает запись видимой, но не делает
     * read-modify-write атомарным. Без него два параллельных delete по одному
     * блоку перезаписывали файл друг поверх друга, и один из них терялся
     * навсегда, вместе с перезаписанным файлом.
     */
    private val tombstoneLock = ReentrantLock()

    @Volatile
    private var tombstones: List<Tombstone> =
        TombstoneFileCodec.read(Files.readAllBytes(dir.resolve("tombstones")))

    override val range: TimeRange get() = meta.range

    override fun select(range: TimeRange, matchers: List<Matcher>): SeriesSet {
        val deleted = tombstones.groupBy({ it.seriesOffset }, { it.range })
        val result = TreeMap<Labels, List<Sample>>(LabelsOrder.COMPARATOR)

        for (offset in contents.lookup(matchers)) {
            val series = contents.seriesAt(offset)
            val ranges = deleted[offset] ?: emptyList()
            val samples = ArrayList<Sample>()

            for (chunk in series.chunks) {
                if (!chunk.range.overlaps(range)) continue
                for (sample in ChunkFileCodec.read(chunkBytes, chunk.ref)) {
                    if (!range.contains(sample.timestamp)) continue
                    if (ranges.any { it.contains(sample.timestamp) }) continue
                    samples.add(sample)
                }
            }
            if (samples.isNotEmpty()) result[series.labels] = samples
        }
        return SortedMapSeriesSet(result)
    }

    /** Сколько маркеров держит серия. Нужно тесту на лок. */
    internal fun tombstoneCountFor(seriesOffset: Int): Int =
        tombstones.count { it.seriesOffset == seriesOffset }

    /** Оффсеты серий, подходящих под матчеры. Нужны delete, чтобы собрать маркеры. */
    fun offsetsOf(matchers: List<Matcher>): List<Int> = contents.lookup(matchers)

    /**
     * Добавляет маркеры удаления. Единственная мутация блока.
     * Файл маленький, поэтому перезаписывается целиком через .tmp и rename.
     */
    fun addTombstones(new: List<Tombstone>) {
        if (new.isEmpty()) return
        tombstoneLock.withLock {
            val merged = coalesce(tombstones + new)

            val staging = dir.resolve("tombstones.tmp")
            FileOutputStream(staging.toFile()).use { out ->
                TombstoneFileCodec.write(out, merged)
                out.flush()
                out.fd.sync()
            }
            Files.move(staging, dir.resolve("tombstones"), StandardCopyOption.ATOMIC_MOVE)
            tombstones = merged
        }
    }

    override fun close() = Unit

    private companion object {
        /**
         * Склеивает пересекающиеся диапазоны внутри каждой серии.
         *
         * Без этого повторный delete того же диапазона дописывал ещё одну
         * запись: 50 одинаковых удалений давали 50 записей и файл на 1213
         * байт вместо 37, и каждое чтение потом проверяло сэмпл против всех
         * 50 диапазонов. В Head то же самое давно делает head/Tombstones.
         */
        fun coalesce(all: List<Tombstone>): List<Tombstone> =
            all.groupBy { it.seriesOffset }
                .toSortedMap()
                .flatMap { (offset, group) ->
                    val merged = ArrayList<TimeRange>(group.size)
                    for (range in group.map { it.range }.sortedBy { it.minTime }) {
                        val last = merged.lastOrNull()
                        if (last != null && range.minTime <= last.maxTime) {
                            if (range.maxTime > last.maxTime) {
                                merged[merged.size - 1] = TimeRange(last.minTime, range.maxTime)
                            }
                        } else {
                            merged.add(range)
                        }
                    }
                    merged.map { Tombstone(offset, it) }
                }
    }
}
