package org.pirsbd.tsdb.storage.codec

import org.pirsbd.tsdb.TestLabels.metric
import org.pirsbd.tsdb.TestLabels.of
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.api.impl.LabelsOrder
import org.pirsbd.tsdb.chunks.ChunkRef
import org.pirsbd.tsdb.index.SeriesChunkMeta
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IndexFileCodecTest {

    private fun chunk(from: Long, to: Long) =
        listOf(SeriesChunkMeta(ChunkRef(0x100000008L), TimeRange(from, to)))

    private fun encode(series: List<IndexSeries>): ByteArray {
        val out = ByteArrayOutputStream()
        IndexFileCodec.write(out, series.sortedWith { a, b -> LabelsOrder.compare(a.labels, b.labels) })
        return out.toByteArray()
    }

    @Test
    fun `series and their chunks round trip`() {
        val series = listOf(
            IndexSeries(metric("cpu", "host" to "a"), chunk(10, 20)),
            IndexSeries(metric("mem"), chunk(30, 40)),
        )

        assertEquals(series, IndexFileCodec.read(encode(series)).all())
    }

    @Test
    fun `symbols are interned once and reused`() {
        val series = (1..50).map { IndexSeries(metric("cpu", "host" to "h$it"), chunk(10, 20)) }

        val contents = IndexFileCodec.read(encode(series))

        assertEquals(50, contents.all().size)
        // __name__, cpu, host, h1..h50 = 53 уникальных символа
        assertEquals(53, contents.symbolCount)
    }

    @Test
    fun `postings intersect on two matchers`() {
        val series = listOf(
            IndexSeries(metric("cpu", "host" to "a"), chunk(10, 20)),
            IndexSeries(metric("cpu", "host" to "b"), chunk(10, 20)),
            IndexSeries(metric("mem", "host" to "a"), chunk(10, 20)),
        )
        val contents = IndexFileCodec.read(encode(series))

        val found = contents.lookup(
            listOf(Matcher.Equals("__name__", "cpu"), Matcher.Equals("host", "a")),
        )

        assertEquals(listOf(metric("cpu", "host" to "a")), found.map { contents.seriesAt(it).labels })
    }

    @Test
    fun `empty matchers select every series through all postings`() {
        val series = listOf(
            IndexSeries(metric("cpu"), chunk(10, 20)),
            IndexSeries(metric("mem"), chunk(10, 20)),
        )
        val contents = IndexFileCodec.read(encode(series))

        assertEquals(2, contents.lookup(emptyList()).size)
    }

    @Test
    fun `lookup returns offsets in labels order`() {
        val series = listOf(
            IndexSeries(metric("mem"), chunk(10, 20)),
            IndexSeries(metric("cpu"), chunk(10, 20)),
            IndexSeries(metric("disk"), chunk(10, 20)),
        )
        val contents = IndexFileCodec.read(encode(series))

        val labels = contents.lookup(emptyList()).map { contents.seriesAt(it).labels }

        assertEquals(listOf(metric("cpu"), metric("disk"), metric("mem")), labels)
    }

    @Test
    fun `a matcher nobody has selects nothing`() {
        val contents = IndexFileCodec.read(encode(listOf(IndexSeries(metric("cpu"), chunk(10, 20)))))

        assertEquals(emptyList(), contents.lookup(listOf(Matcher.Equals("host", "nope"))))
    }

    @Test
    fun `a duplicated label name stays deterministic`() {
        // Labels.of дедупликации не делает, порядок задаёт Label.compareTo по (name, value)
        val labels = of("__name__" to "cpu", "host" to "b", "host" to "a")
        val contents = IndexFileCodec.read(encode(listOf(IndexSeries(labels, chunk(10, 20)))))

        val back = contents.all()[0].labels

        assertEquals(labels.toList(), back.toList())
        assertEquals(listOf("a", "b"), back.filter { it.name == "host" }.map { it.value })
    }

    @Test
    fun `an index with no series round trips`() {
        val contents = IndexFileCodec.read(encode(emptyList()))

        assertEquals(emptyList(), contents.all())
        assertEquals(0, contents.symbolCount)
    }

    @Test
    fun `a file shorter than the toc is rejected`() {
        val bytes = encode(listOf(IndexSeries(metric("cpu"), chunk(10, 20))))

        assertFailsWith<CorruptBlockException> { IndexFileCodec.read(bytes.copyOf(20)) }
    }

    @Test
    fun `a flipped byte in the symbol table is caught by the crc`() {
        val bytes = encode(listOf(IndexSeries(metric("cpu"), chunk(10, 20))))
        // 5 байт заголовка, затем len:int32 и count:int32 секции символов
        bytes[18] = (bytes[18] + 1).toByte()

        assertFailsWith<CorruptBlockException> { IndexFileCodec.read(bytes).all() }
    }
}
