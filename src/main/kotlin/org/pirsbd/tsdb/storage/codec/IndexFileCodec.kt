package org.pirsbd.tsdb.storage.codec

import org.pirsbd.tsdb.api.Label
import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.chunks.ChunkRef
import org.pirsbd.tsdb.index.SeriesChunkMeta
import java.io.ByteArrayOutputStream
import java.io.OutputStream

internal data class IndexSeries(val labels: Labels, val chunks: List<SeriesChunkMeta>)

/**
 * index:
 *   magic:int32, version:int8,
 *   Symbol Table, Series, Postings 1..N, Postings Offset Table,
 *   TOC (36 байт, в самом конце файла)
 *
 * TOC лежит в конце, а не в начале, потому что писатель узнаёт оффсеты
 * секций только после того, как их записал. Размер фиксированный, поэтому
 * читателю хватает одного seek(fileLength - 36).
 *
 * Запись с парой ("", "") в Postings Offset Table хранит список всех серий,
 * это allPostings из Prometheus. Без неё пустой список матчеров пришлось бы
 * обслуживать полным сканом секции Series.
 */
internal object IndexFileCodec {
    const val MAGIC: Int = 0xBAAAD700.toInt()
    const val VERSION: Int = 1
    const val TOC_SIZE: Int = 36

    val ALL_POSTINGS_LABEL = Label("", "")

    fun write(out: OutputStream, series: List<IndexSeries>) {
        val sink = CountingOutput(out)
        sink.writeInt(MAGIC)
        sink.writeByte(VERSION)

        val symbols = LinkedHashMap<String, Int>()
        for (entry in series) {
            for (label in entry.labels) {
                symbols.getOrPut(label.name) { symbols.size }
                symbols.getOrPut(label.value) { symbols.size }
            }
        }

        val symbolsAt = sink.position
        writeSection(sink) { body ->
            body.writeInt(symbols.size)
            for (symbol in symbols.keys) body.writeUtf8(symbol)
        }

        val seriesAt = sink.position
        val seriesOffsets = ArrayList<Long>(series.size)
        for (entry in series) {
            seriesOffsets.add(sink.position)
            writeSection(sink) { body ->
                body.writeInt(entry.labels.size)
                for (label in entry.labels) {
                    body.writeInt(symbols.getValue(label.name))
                    body.writeInt(symbols.getValue(label.value))
                }
                body.writeInt(entry.chunks.size)
                for (meta in entry.chunks) {
                    body.writeLong(meta.range.minTime)
                    body.writeLong(meta.range.maxTime)
                    body.writeLong(meta.ref.value)
                }
            }
        }

        val postings = LinkedHashMap<Label, MutableList<Long>>()
        postings[ALL_POSTINGS_LABEL] = ArrayList(seriesOffsets)
        for ((ordinal, entry) in series.withIndex()) {
            for (label in entry.labels) {
                postings.getOrPut(label) { ArrayList() }.add(seriesOffsets[ordinal])
            }
        }

        val postingsAt = sink.position
        val postingsOffsets = LinkedHashMap<Label, Long>()
        for ((label, refs) in postings) {
            postingsOffsets[label] = sink.position
            writeSection(sink) { body ->
                val sorted = refs.sorted()
                body.writeInt(sorted.size)
                for (ref in sorted) body.writeLong(ref)
            }
        }

        val offsetTableAt = sink.position
        writeSection(sink) { body ->
            body.writeInt(postingsOffsets.size)
            for ((label, offset) in postingsOffsets) {
                body.writeUtf8(label.name)
                body.writeUtf8(label.value)
                body.writeLong(offset)
            }
        }

        val toc = ByteArrayOutputStream()
        val tocBody = CountingOutput(toc)
        tocBody.writeLong(symbolsAt)
        tocBody.writeLong(seriesAt)
        tocBody.writeLong(postingsAt)
        tocBody.writeLong(offsetTableAt)
        val tocBytes = toc.toByteArray()
        sink.writeBytes(tocBytes)
        sink.writeInt(crc32(tocBytes, 0, tocBytes.size))
        sink.flush()
    }

    fun read(bytes: ByteArray): IndexContents {
        if (bytes.size < TOC_SIZE) {
            throw CorruptBlockException("index shorter than the toc: ${bytes.size} < $TOC_SIZE")
        }
        val reader = ByteReader(bytes)
        if (reader.readInt() != MAGIC) throw CorruptBlockException("bad index magic")
        if (reader.readByte() != VERSION) throw CorruptBlockException("bad index version")

        reader.seek(bytes.size - TOC_SIZE)
        val tocAt = reader.position
        val symbolsAt = reader.readLong()
        reader.readLong() // series start, нужен только для отладки
        reader.readLong() // postings start, нужен только для отладки
        val offsetTableAt = reader.readLong()
        reader.verifyCrc(tocAt, TOC_SIZE - 4)

        reader.seek(symbolsAt.toInt())
        val symbols = readSection(reader) { body ->
            val count = body.readInt()
            if (count < 0) throw CorruptBlockException("negative symbol count: $count")
            ArrayList<String>(count).also { list -> repeat(count) { list.add(body.readUtf8()) } }
        }

        reader.seek(offsetTableAt.toInt())
        val offsetTable = readSection(reader) { body ->
            val count = body.readInt()
            if (count < 0) throw CorruptBlockException("negative offset table count: $count")
            val map = LinkedHashMap<Label, Long>(count)
            repeat(count) {
                val name = body.readUtf8()
                val value = body.readUtf8()
                map[Label(name, value)] = body.readLong()
            }
            map
        }

        return IndexContents(bytes, symbols, offsetTable)
    }

    private inline fun writeSection(sink: CountingOutput, body: (CountingOutput) -> Unit) {
        val buffer = ByteArrayOutputStream()
        body(CountingOutput(buffer))
        val payload = buffer.toByteArray()
        sink.writeInt(payload.size)
        sink.writeBytes(payload)
        sink.writeInt(crc32(payload, 0, payload.size))
    }

    private inline fun <T> readSection(reader: ByteReader, body: (ByteReader) -> T): T {
        val length = reader.readInt()
        if (length < 0) throw CorruptBlockException("negative section length: $length")
        val at = reader.position
        val value = body(reader)
        if (reader.position - at != length) {
            throw CorruptBlockException("section length mismatch at $at: read ${reader.position - at}, declared $length")
        }
        reader.verifyCrc(at, length)
        return value
    }
}

/**
 * Разобранный индекс. Символы и таблица оффсетов подняты в память, серии
 * читаются по требованию: их много, и запросу нужны не все.
 */
internal class IndexContents(
    private val bytes: ByteArray,
    private val symbols: List<String>,
    private val postingsOffsets: Map<Label, Long>,
) {
    val symbolCount: Int get() = symbols.size

    /**
     * Оффсеты подходящих серий по возрастанию. Серии записаны в порядке
     * Labels, поэтому возрастающий оффсет это и есть порядок Labels.
     */
    fun lookup(matchers: List<Matcher>): List<Int> {
        if (matchers.isEmpty()) {
            return postingsOffsets[IndexFileCodec.ALL_POSTINGS_LABEL]
                ?.let { readPostings(it) }
                ?: emptyList()
        }

        val lists = ArrayList<List<Int>>(matchers.size)
        for (matcher in matchers) {
            val equals = matcher as Matcher.Equals
            val offset = postingsOffsets[Label(equals.name, equals.value)] ?: return emptyList()
            lists.add(readPostings(offset))
        }

        val shortest = lists.minBy { it.size }
        return shortest.filter { candidate ->
            lists.all { it === shortest || it.binarySearch(candidate) >= 0 }
        }
    }

    fun all(): List<IndexSeries> = lookup(emptyList()).map { seriesAt(it) }

    fun seriesAt(offset: Int): IndexSeries {
        val reader = ByteReader(bytes)
        reader.seek(offset)
        val length = reader.readInt()
        if (length < 0) throw CorruptBlockException("negative series length: $length")
        val at = reader.position

        val labelCount = reader.readInt()
        if (labelCount < 0) throw CorruptBlockException("negative label count: $labelCount")
        val labels = ArrayList<Label>(labelCount)
        repeat(labelCount) {
            val name = symbolAt(reader.readInt())
            val value = symbolAt(reader.readInt())
            labels.add(Label(name, value))
        }

        val chunkCount = reader.readInt()
        if (chunkCount < 0) throw CorruptBlockException("negative chunk count: $chunkCount")
        val chunks = ArrayList<SeriesChunkMeta>(chunkCount)
        repeat(chunkCount) {
            val minTime = reader.readLong()
            val maxTime = reader.readLong()
            chunks.add(SeriesChunkMeta(ChunkRef(reader.readLong()), TimeRange(minTime, maxTime)))
        }

        if (reader.position - at != length) {
            throw CorruptBlockException("series length mismatch at $offset")
        }
        reader.verifyCrc(at, length)
        // Labels(labels), а не Labels.of: серии записаны уже отсортированными,
        // повторная сортировка затёрла бы порядок одноимённых лейблов.
        return IndexSeries(Labels(labels), chunks)
    }

    private fun symbolAt(id: Int): String {
        if (id !in symbols.indices) throw CorruptBlockException("symbol id out of range: $id")
        return symbols[id]
    }

    private fun readPostings(offset: Long): List<Int> {
        val reader = ByteReader(bytes)
        reader.seek(offset.toInt())
        val length = reader.readInt()
        if (length < 0) throw CorruptBlockException("negative postings length: $length")
        val at = reader.position
        val count = reader.readInt()
        if (count < 0) throw CorruptBlockException("negative postings count: $count")
        val refs = ArrayList<Int>(count)
        repeat(count) { refs.add(reader.readLong().toInt()) }
        if (reader.position - at != length) {
            throw CorruptBlockException("postings length mismatch at $offset")
        }
        reader.verifyCrc(at, length)
        return refs
    }
}
