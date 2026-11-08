package org.pirsbd.tsdb.storage

import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.api.impl.LabelsOrder
import org.pirsbd.tsdb.head.HeadSnapshot
import org.pirsbd.tsdb.index.SeriesChunkMeta
import org.pirsbd.tsdb.storage.codec.BlockMetaJson
import org.pirsbd.tsdb.storage.codec.ChunkFileCodec
import org.pirsbd.tsdb.storage.codec.IndexFileCodec
import org.pirsbd.tsdb.storage.codec.IndexSeries
import org.pirsbd.tsdb.storage.codec.Tombstone
import org.pirsbd.tsdb.storage.codec.TombstoneFileCodec
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * Пишет блок в <ulid>.tmp, делает fsync каждого файла, затем атомарно
 * переименовывает папку в <ulid>. Блок либо целиком на месте, либо его нет.
 *
 * Если процесс упал в середине, на диске остаётся .tmp, его вычищает
 * FileStorage при открытии.
 */
internal class FileBlockWriter(private val dir: Path) {

    fun write(snapshot: HeadSnapshot): DiskBlock {
        val series = snapshot.series()
            .sortedWith { a, b -> LabelsOrder.compare(a.labels, b.labels) }
        require(series.isNotEmpty()) { "refusing to write an empty block" }
        require(series.all { it.samples.isNotEmpty() }) { "a snapshot series must have samples" }

        Files.createDirectories(dir)
        val ulid = newUlid()
        val staging = dir.resolve("$ulid.tmp")
        Files.createDirectories(staging.resolve("chunks"))

        // Один чанк на серию: нарезка чанков по размеру это оптимизация,
        // в MVP она ничего не меняет в структуре файла.
        val chunkBytes = ByteArrayOutputStream()
        val refs = ChunkFileCodec.write(chunkBytes, series.map { it.samples })
        writeAndSync(staging.resolve("chunks/000001"), chunkBytes.toByteArray())

        val indexBytes = ByteArrayOutputStream()
        IndexFileCodec.write(
            indexBytes,
            series.mapIndexed { i, entry ->
                IndexSeries(
                    labels = entry.labels,
                    chunks = listOf(
                        SeriesChunkMeta(
                            ref = refs[i],
                            range = TimeRange(
                                entry.samples.first().timestamp,
                                entry.samples.last().timestamp,
                            ),
                        ),
                    ),
                )
            },
        )
        val indexContent = indexBytes.toByteArray()
        writeAndSync(staging.resolve("index"), indexContent)

        // Оффсеты серий известны только после записи индекса, поэтому
        // маркеры из снимка переводятся из Labels в оффсеты здесь.
        val contents = IndexFileCodec.read(indexContent)
        val offsetByLabels = contents.lookup(emptyList()).associateBy { contents.seriesAt(it).labels }
        val tombstoneBytes = ByteArrayOutputStream()
        TombstoneFileCodec.write(
            tombstoneBytes,
            snapshot.tombstones().mapNotNull { t -> offsetByLabels[t.labels]?.let { Tombstone(it, t.range) } },
        )
        writeAndSync(staging.resolve("tombstones"), tombstoneBytes.toByteArray())

        val meta = BlockMeta(
            ulid = ulid,
            range = snapshot.range,
            stats = BlockStats(
                numSamples = snapshot.numSamples,
                numSeries = snapshot.numSeries.toLong(),
                numChunks = refs.size.toLong(),
            ),
            compaction = Compaction(level = 1, sources = emptyList()),
            version = 1,
        )
        writeAndSync(staging.resolve("meta.json"), BlockMetaJson.encode(meta).toByteArray())

        val target = dir.resolve(ulid)
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
        return DiskBlock(target)
    }

    private fun writeAndSync(path: Path, content: ByteArray) {
        Files.createDirectories(path.parent)
        FileOutputStream(path.toFile()).use { out ->
            out.write(content)
            out.flush()
            out.fd.sync()
        }
    }

    private companion object {
        private val random = SecureRandom()
        private val counter = AtomicLong(0)
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        /**
         * 26 символов Crockford base32: 10 из времени, 8 из счётчика, 8 случайных.
         *
         * Счётчик стоит перед случайной частью, поэтому два блока, записанных в
         * одну миллисекунду, всё равно сортируются в порядке создания:
         * лексикографический порядок папок совпадает с хронологическим.
         */
        fun newUlid(): String {
            val now = System.currentTimeMillis()
            val sequence = counter.getAndIncrement()
            val entropy = random.nextLong()

            val sb = StringBuilder(26)
            for (i in 9 downTo 0) sb.append(ALPHABET[((now ushr (i * 5)) and 0x1FL).toInt()])
            for (i in 7 downTo 0) sb.append(ALPHABET[((sequence ushr (i * 5)) and 0x1FL).toInt()])
            for (i in 7 downTo 0) sb.append(ALPHABET[((entropy ushr (i * 5)) and 0x1FL).toInt()])
            return sb.toString()
        }
    }
}
