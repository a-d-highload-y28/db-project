package org.pirsbd.tsdb.storage

import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.head.HeadSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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
    private val writeLock = ReentrantLock()

    @Volatile
    private var blocks: List<DiskBlock>

    init {
        Files.createDirectories(dir)
        cleanStaging()
        blocks = openAll()
    }

    fun blocks(): List<DiskBlock> = blocks

    fun overlapping(range: TimeRange): List<DiskBlock> = blocks.filter { it.range.overlaps(range) }

    /**
     * Лок обязателен: @Volatile делает запись видимой, но read-modify-write
     * списка блоков атомарным не делает. Без него два параллельных write
     * перезаписывали список друг поверх друга, и уже лежащий на диске блок
     * исчезал из запросов до рестарта процесса.
     */
    fun write(snapshot: HeadSnapshot): DiskBlock = writeLock.withLock {
        val block = writer.write(snapshot)
        blocks = (blocks + block).sortedBy { it.meta.range.minTime }
        block
    }

    override fun close() {
        blocks.forEach { it.close() }
    }

    private fun cleanStaging() {
        Files.list(dir).use { stream ->
            stream.filter { it.name.endsWith(".tmp") }.toList().forEach { deleteRecursively(it) }
        }
    }

    /**
     * Блок это папка с meta.json. Папка без него нашей не является:
     * lost+found рядом с данными не должен ронять старт сырым
     * NoSuchFileException. Битый meta.json или индекс, наоборот, падают
     * громко: молча пропустить блок значит потерять данные молча.
     */
    private fun openAll(): List<DiskBlock> =
        Files.list(dir).use { stream ->
            stream.filter { it.isDirectory() }.toList()
                .filter { Files.isReadable(it.resolve("meta.json")) }
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
