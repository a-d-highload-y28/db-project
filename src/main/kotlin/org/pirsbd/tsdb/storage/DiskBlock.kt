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

    /** Оффсеты серий, подходящих под матчеры. Нужны delete, чтобы собрать маркеры. */
    fun offsetsOf(matchers: List<Matcher>): List<Int> = contents.lookup(matchers)

    /**
     * Добавляет маркеры удаления. Единственная мутация блока.
     * Файл маленький, поэтому перезаписывается целиком через .tmp и rename.
     */
    fun addTombstones(new: List<Tombstone>) {
        if (new.isEmpty()) return
        tombstoneLock.withLock {
            val merged = tombstones + new

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
}
