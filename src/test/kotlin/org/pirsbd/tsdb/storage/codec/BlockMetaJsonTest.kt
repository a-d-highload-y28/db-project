package org.pirsbd.tsdb.storage.codec

import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.storage.BlockMeta
import org.pirsbd.tsdb.storage.BlockStats
import org.pirsbd.tsdb.storage.Compaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlockMetaJsonTest {

    private val meta = BlockMeta(
        ulid = "01EM6Q6A1YPX4G9TEB20J22B2R",
        range = TimeRange(1602237600000, 1602244800000),
        stats = BlockStats(numSamples = 553, numSeries = 13, numChunks = 44),
        compaction = Compaction(level = 1, sources = emptyList()),
        version = 1,
    )

    @Test
    fun `meta round trips`() {
        assertEquals(meta, BlockMetaJson.decode(BlockMetaJson.encode(meta)))
    }

    @Test
    fun `meta json is human readable for the defense`() {
        val text = BlockMetaJson.encode(meta)

        assertTrue(text.contains("\n"), "should be pretty printed")
        assertTrue(text.contains("\"minTime\""))
        assertTrue(text.contains("\"numSamples\""))
    }

    @Test
    fun `compaction sources round trip`() {
        val withSources = meta.copy(compaction = Compaction(level = 2, sources = listOf("a", "b")))

        assertEquals(withSources, BlockMetaJson.decode(BlockMetaJson.encode(withSources)))
    }
}
