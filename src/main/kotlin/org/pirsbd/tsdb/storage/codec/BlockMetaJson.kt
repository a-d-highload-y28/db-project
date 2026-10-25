package org.pirsbd.tsdb.storage.codec

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.storage.BlockMeta
import org.pirsbd.tsdb.storage.BlockStats
import org.pirsbd.tsdb.storage.Compaction

/**
 * meta.json по образцу из docs/structure-and-usecases.md.
 *
 * Pretty print не для красоты: на защите файл открывается глазами через cat.
 * Руками парсить JSON было бы лишними багами на пустом месте.
 */
internal object BlockMetaJson {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Serializable
    private data class StatsDto(val numSamples: Long, val numSeries: Long, val numChunks: Long)

    @Serializable
    private data class CompactionDto(val level: Int, val sources: List<String> = emptyList())

    @Serializable
    private data class MetaDto(
        val ulid: String,
        val minTime: Long,
        val maxTime: Long,
        val stats: StatsDto,
        val compaction: CompactionDto,
        val version: Int,
    )

    fun encode(meta: BlockMeta): String = json.encodeToString(
        MetaDto.serializer(),
        MetaDto(
            ulid = meta.ulid,
            minTime = meta.range.minTime,
            maxTime = meta.range.maxTime,
            stats = StatsDto(meta.stats.numSamples, meta.stats.numSeries, meta.stats.numChunks),
            compaction = CompactionDto(meta.compaction.level, meta.compaction.sources),
            version = meta.version,
        ),
    )

    fun decode(text: String): BlockMeta {
        val dto = json.decodeFromString(MetaDto.serializer(), text)
        return BlockMeta(
            ulid = dto.ulid,
            range = TimeRange(dto.minTime, dto.maxTime),
            stats = BlockStats(dto.stats.numSamples, dto.stats.numSeries, dto.stats.numChunks),
            compaction = Compaction(dto.compaction.level, dto.compaction.sources),
            version = dto.version,
        )
    }
}
