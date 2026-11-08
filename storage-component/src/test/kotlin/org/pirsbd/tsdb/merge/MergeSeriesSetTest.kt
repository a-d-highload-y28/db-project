package org.pirsbd.tsdb.merge

import org.pirsbd.tsdb.TestLabels.drain
import org.pirsbd.tsdb.TestLabels.metric
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.api.impl.MergeSeriesSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private val ALL = TimeRange(0, 1_000)

class MergeSeriesSetTest {

    private fun merge(vararg sources: FakeSeriesSource) =
        MergeSeriesSet(sources.map { it.select(ALL, emptyList()) })

    @Test
    fun `1 different labels come out ordered by labels`() {
        val a = FakeSeriesSource(mapOf(metric("mem") to listOf(Sample(10, 2.0))))
        val b = FakeSeriesSource(mapOf(metric("cpu") to listOf(Sample(10, 1.0))))

        val got = drain(merge(a, b))

        assertEquals(listOf(metric("cpu"), metric("mem")), got.map { it.first })
    }

    @Test
    fun `2 same labels in two sources merge their samples by timestamp`() {
        val head = FakeSeriesSource(mapOf(metric("cpu") to listOf(Sample(30, 3.0))))
        val block = FakeSeriesSource(mapOf(metric("cpu") to listOf(Sample(10, 1.0), Sample(20, 2.0))))

        val got = drain(merge(head, block))

        assertEquals(1, got.size)
        assertEquals(listOf(Sample(10, 1.0), Sample(20, 2.0), Sample(30, 3.0)), got[0].second)
    }

    @Test
    fun `3 on an equal timestamp the first source wins`() {
        val head = FakeSeriesSource(mapOf(metric("cpu") to listOf(Sample(10, 999.0))))
        val block = FakeSeriesSource(mapOf(metric("cpu") to listOf(Sample(10, 1.0))))

        val got = drain(merge(head, block))

        assertEquals(listOf(Sample(10, 999.0)), got[0].second)
    }

    @Test
    fun `4 an empty source in the middle is skipped`() {
        val a = FakeSeriesSource(mapOf(metric("cpu") to listOf(Sample(10, 1.0))))
        val empty = FakeSeriesSource(emptyMap())
        val c = FakeSeriesSource(mapOf(metric("mem") to listOf(Sample(10, 2.0))))

        val got = drain(merge(a, empty, c))

        assertEquals(listOf(metric("cpu"), metric("mem")), got.map { it.first })
    }

    @Test
    fun `5 three sources with overlapping ranges`() {
        val a = FakeSeriesSource(mapOf(metric("cpu") to listOf(Sample(10, 1.0), Sample(40, 4.0))))
        val b = FakeSeriesSource(mapOf(metric("cpu") to listOf(Sample(20, 2.0))))
        val c = FakeSeriesSource(mapOf(metric("cpu") to listOf(Sample(30, 3.0), Sample(50, 5.0))))

        val got = drain(merge(a, b, c))

        assertEquals(listOf(10L, 20L, 30L, 40L, 50L), got[0].second.map { it.timestamp })
    }

    @Test
    fun `6 all sources empty means next is false right away`() {
        merge(FakeSeriesSource(emptyMap()), FakeSeriesSource(emptyMap())).use {
            assertFalse(it.next())
        }
    }

    @Test
    fun `7 merging nothing at all is also false`() {
        MergeSeriesSet(emptyList()).use { assertFalse(it.next()) }
    }

    @Test
    fun `8 a series present in one source only keeps its own samples`() {
        val a = FakeSeriesSource(
            mapOf(
                metric("cpu") to listOf(Sample(10, 1.0)),
                metric("disk") to listOf(Sample(70, 7.0)),
            ),
        )
        val b = FakeSeriesSource(mapOf(metric("mem") to listOf(Sample(20, 2.0))))

        val got = drain(merge(a, b))

        assertEquals(listOf(metric("cpu"), metric("disk"), metric("mem")), got.map { it.first })
        assertEquals(listOf(Sample(70, 7.0)), got[1].second)
    }
}
