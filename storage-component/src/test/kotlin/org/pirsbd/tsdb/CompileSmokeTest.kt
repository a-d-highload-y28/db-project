package org.pirsbd.tsdb

import org.pirsbd.tsdb.api.Label
import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.api.SeriesSet
import org.pirsbd.tsdb.index.SeriesRef
import kotlin.test.Test
import kotlin.test.assertEquals

object TestLabels {
    fun of(vararg pairs: Pair<String, String>): Labels =
        Labels(pairs.map { Label(it.first, it.second) }.sorted())

    fun metric(name: String, vararg pairs: Pair<String, String>): Labels =
        of(*(arrayOf(Labels.METRIC_NAME to name) + pairs))

    fun drain(set: SeriesSet): List<Pair<Labels, List<Sample>>> {
        val out = ArrayList<Pair<Labels, List<Sample>>>()
        set.use {
            while (it.next()) {
                val series = it.at()
                val samples = ArrayList<Sample>()
                series.iterator().use { si -> while (si.next()) samples.add(si.at()) }
                out.add(series.labels to samples)
            }
        }
        return out
    }
}

class CompileSmokeTest {
    @Test
    fun `SeriesRef is usable from a public context`() {
        val ref: SeriesRef = SeriesRef(42L)
        assertEquals(42L, ref.value)
    }

    @Test
    fun `metric helper puts the name into __name__ and sorts labels`() {
        val labels = TestLabels.metric("cpu", "host" to "a")

        assertEquals("cpu", labels.get(Labels.METRIC_NAME))
        assertEquals(listOf("__name__", "host"), labels.map { it.name })
    }
}
