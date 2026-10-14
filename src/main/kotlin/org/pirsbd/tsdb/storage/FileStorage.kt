package org.pirsbd.tsdb.storage

import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.head.HeadSnapshot
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Каталог блоков. При открытии вычищает недописанные .tmp и поднимает
 * остальные папки как DiskBlock: именно это делает сброшенные данные
 * видимыми после рестарта.
 *
 * Компакции в лабе 2 нет, блоки не удаляются, поэтому открытый querier
 * не может остаться со ссылкой на снесённый блок.
 */
internal class FileStorage(private val dir: Path) : AutoCloseable {

    private val writer = FileBlockWriter(dir)

    @Volatile
    private var blocks: List<DiskBlock>

    init {
        Files.createDirectories(dir)
        cleanStaging()
        blocks = openAll()
    }

    fun blocks(): List<DiskBlock> = blocks

    fun overlapping(range: TimeRange): List<DiskBlock> = blocks.filter { it.range.overlaps(range) }

    fun write(snapshot: HeadSnapshot): DiskBlock {
        val block = writer.write(snapshot)
        blocks = (blocks + block).sortedBy { it.meta.range.minTime }
        return block
    }

    override fun close() {
        blocks.forEach { it.close() }
    }

    private fun cleanStaging() {
        Files.list(dir).use { stream ->
            stream.filter { it.name.endsWith(".tmp") }.toList().forEach { deleteRecursively(it) }
        }
    }

    /** Папки это блоки, всё остальное рядом игнорируется. */
    private fun openAll(): List<DiskBlock> =
        Files.list(dir).use { stream ->
            stream.filter { it.isDirectory() }.toList()
                .map { DiskBlock(it) }
                .sortedBy { it.meta.range.minTime }
        }

    private fun deleteRecursively(path: Path) {
        if (path.isDirectory()) {
            Files.list(path).use { stream -> stream.toList().forEach { deleteRecursively(it) } }
        }
        Files.deleteIfExists(path)
    }
}
