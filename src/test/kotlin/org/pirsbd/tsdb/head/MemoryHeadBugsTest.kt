package org.pirsbd.tsdb.head

import org.pirsbd.tsdb.TestLabels.drain
import org.pirsbd.tsdb.TestLabels.metric
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val ALL = TimeRange(0, 1_000)
private fun cpu() = metric("cpu")
private fun eq(name: String, value: String) = listOf(Matcher.Equals(name, value))

class MemoryHeadBugsTest {

    @Test
    fun `B empty matchers select all series`() {
        val head = MemoryHead()
        head.append(metric("cpu"), 10, 1.0)
        head.append(metric("mem"), 10, 2.0)

        val got = drain(head.select(ALL, emptyList()))

        assertEquals(2, got.size)
        assertEquals(listOf(metric("cpu"), metric("mem")), got.map { it.first })
    }

    @Test
    fun `C series iterator is fresh on every call`() {
        val head = MemoryHead()
        head.append(cpu(), 10, 1.0)

        head.select(ALL, eq("__name__", "cpu")).use { set ->
            assertEquals(true, set.next())
            val series = set.at()

            val first = ArrayList<Sample>()
            series.iterator().use { while (it.next()) first.add(it.at()) }
            val second = ArrayList<Sample>()
            series.iterator().use { while (it.next()) second.add(it.at()) }

            assertEquals(first, second)
            assertEquals(listOf(Sample(10, 1.0)), first)
        }
    }

    @Test
    fun `D duplicate timestamp is overwritten by the last value`() {
        val head = MemoryHead()
        head.append(cpu(), 10, 1.0)
        head.append(cpu(), 10, 99.0)

        val samples = drain(head.select(ALL, eq("__name__", "cpu")))[0].second

        assertEquals(listOf(Sample(10, 99.0)), samples)
    }

    @Test
    fun `D2 duplicate timestamp is overwritten on the out of order path`() {
        val head = MemoryHead()
        head.append(cpu(), 30, 3.0)
        head.append(cpu(), 10, 1.0)
        head.append(cpu(), 10, 77.0)

        val samples = drain(head.select(ALL, eq("__name__", "cpu")))[0].second

        assertEquals(listOf(Sample(10, 77.0), Sample(30, 3.0)), samples)
    }

    @Test
    fun `E an empty head reports EMPTY and never trips TimeRange's own require`() {
        val head = MemoryHead()

        // Прежняя версия этого теста утверждала «не пересекается ни с чем», но
        // проверяла только диапазон до Long.MAX_VALUE - 1, то есть единственную
        // границу, при которой это верно. Пустота решается isEmpty, а не
        // сравнением диапазонов.
        assertEquals(TimeRange.EMPTY, head.range)
        assertTrue(head.isEmpty)
        assertFalse(head.select(TimeRange(Long.MIN_VALUE, Long.MAX_VALUE), emptyList()).use { it.next() })
    }

    @Test
    fun `F append after close fails loudly`() {
        val head = MemoryHead()
        head.close()

        assertFailsWith<IllegalStateException> { head.append(cpu(), 10, 1.0) }
    }
}
