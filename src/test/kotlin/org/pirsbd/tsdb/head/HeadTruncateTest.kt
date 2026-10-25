package org.pirsbd.tsdb.head

import org.pirsbd.tsdb.TestLabels.drain
import org.pirsbd.tsdb.TestLabels.metric
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private val ALL = TimeRange(0, 1_000)

class HeadTruncateTest {

    @Test
    fun `truncate of an empty head returns null`() {
        assertNull(MemoryHead().truncate(Long.MAX_VALUE))
    }

    @Test
    fun `truncate with nothing below the bound returns null`() {
        val head = MemoryHead()
        head.append(metric("cpu"), 100, 1.0)

        assertNull(head.truncate(50))
    }

    @Test
    fun `truncate MAX takes everything and leaves the head empty`() {
        val head = MemoryHead()
        head.append(metric("cpu"), 10, 1.0)
        head.append(metric("cpu"), 20, 2.0)

        val snapshot = assertNotNull(head.truncate(Long.MAX_VALUE))

        assertEquals(1, snapshot.numSeries)
        assertEquals(2, snapshot.numSamples)
        assertEquals(TimeRange(10, 20), snapshot.range)
        assertEquals(TimeRange.EMPTY, head.range)
        assertEquals(emptyList(), drain(head.select(ALL, emptyList())).flatMap { it.second })
    }

    @Test
    fun `a partial truncate keeps the fresh samples in the head`() {
        val head = MemoryHead()
        for (t in 1L..5L) head.append(metric("cpu"), t, t.toDouble())

        val snapshot = assertNotNull(head.truncate(3))

        assertEquals(listOf(Sample(1, 1.0), Sample(2, 2.0)), snapshot.series()[0].samples)
        assertEquals(TimeRange(3, 5), head.range)
        assertEquals(
            listOf(3L, 4L, 5L),
            drain(head.select(ALL, emptyList()))[0].second.map { it.timestamp },
        )
    }

    @Test
    fun `snapshot series come out in labels order`() {
        val head = MemoryHead()
        head.append(metric("mem"), 10, 1.0)
        head.append(metric("cpu"), 10, 2.0)
        head.append(metric("disk"), 10, 3.0)

        val snapshot = assertNotNull(head.truncate(Long.MAX_VALUE))

        assertEquals(
            listOf(metric("cpu"), metric("disk"), metric("mem")),
            snapshot.series().map { it.labels },
        )
    }

    @Test
    fun `snapshot carries the tombstones written into the head`() {
        val head = MemoryHead()
        for (t in 1L..5L) head.append(metric("cpu"), t, t.toDouble())
        head.delete(TimeRange(2, 3), listOf(Matcher.Equals("__name__", "cpu")))

        val snapshot = assertNotNull(head.truncate(Long.MAX_VALUE))

        assertEquals(1, snapshot.tombstones().size)
        assertEquals(metric("cpu"), snapshot.tombstones()[0].labels)
        assertEquals(TimeRange(2, 3), snapshot.tombstones()[0].range)
    }

    @Test
    fun `out of order samples are drained in timestamp order`() {
        val head = MemoryHead()
        head.append(metric("cpu"), 30, 3.0)
        head.append(metric("cpu"), 10, 1.0)
        head.append(metric("cpu"), 20, 2.0)

        val snapshot = assertNotNull(head.truncate(Long.MAX_VALUE))

        assertEquals(listOf(10L, 20L, 30L), snapshot.series()[0].samples.map { it.timestamp })
    }

    @Test
    fun `a second truncate after a full one returns null`() {
        val head = MemoryHead()
        head.append(metric("cpu"), 10, 1.0)
        head.truncate(Long.MAX_VALUE)

        assertNull(head.truncate(Long.MAX_VALUE))
    }
}
