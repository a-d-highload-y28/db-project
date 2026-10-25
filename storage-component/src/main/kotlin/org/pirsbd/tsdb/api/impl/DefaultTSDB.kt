package org.pirsbd.tsdb.api.impl

import org.pirsbd.tsdb.api.Appender
import org.pirsbd.tsdb.api.Labels
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.Querier
import org.pirsbd.tsdb.api.SeriesSet
import org.pirsbd.tsdb.api.SeriesSource
import org.pirsbd.tsdb.api.TSDB
import org.pirsbd.tsdb.api.TSDBConfig
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.head.MemoryHead
import org.pirsbd.tsdb.storage.FileStorage
import org.pirsbd.tsdb.storage.codec.Tombstone
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

class DefaultTSDB(
    dir: Path,
    private val config: TSDBConfig = TSDBConfig(),
) : TSDB {

    private val head = MemoryHead(config.stripeSize)
    private val storage = FileStorage(dir)

    /**
     * Один лок на уровне БД поверх flush, delete и выбора источников.
     *
     * Он закрывает три вещи сразу. Без него truncate уже убрал сэмплы из Head,
     * а блок ещё не опубликован, и читатель в этом окне не видит их нигде.
     * Два параллельных flush перезаписывали список блоков друг поверх друга, и
     * уже записанный на диск блок пропадал из запросов до рестарта. И delete,
     * разошедшийся с flush, мог не увидеть блок, в который как раз уехали
     * удаляемые сэмплы.
     */
    private val dbLock = ReentrantReadWriteLock()

    override fun appender(): Appender = HeadAppender(head) { maybeAutoFlush() }

    override fun querier(range: TimeRange): Querier = DefaultQuerier(range, ::selectAtomically)

    /**
     * Выбор источников и снятие с них данных идут под одним читательским
     * локом. Разорвать эти два шага нельзя: флаш между ними уносит сэмплы из
     * Head в блок, которого в списке ещё нет.
     *
     * Оба источника материализуют свои сэмплы внутри select, поэтому к моменту
     * выхода из лока результат уже снимок и MVCC не нужен.
     */
    private fun selectAtomically(range: TimeRange, matchers: List<Matcher>): SeriesSet =
        dbLock.readLock().withLock {
            MergeSeriesSet(sourcesFor(range).map { it.select(range, matchers) })
        }

    /** Head идёт первым: при равном таймстемпе он должен перебить блок. */
    internal fun sourcesFor(range: TimeRange): List<SeriesSource> = dbLock.readLock().withLock {
        val sources = ArrayList<SeriesSource>()
        if (!head.isEmpty && head.range.overlaps(range)) sources.add(head)
        sources.addAll(storage.overlapping(range))
        sources
    }

    override fun delete(range: TimeRange, matchers: List<Matcher>) {
        val blocks = dbLock.readLock().withLock {
            head.delete(range, matchers)
            storage.overlapping(range)
        }

        // Запись маркеров идёт вне dbLock: каждый блок защищён своим
        // tombstoneLock, а fsync по каждому блоку под глобальным локом
        // останавливал бы всех писателей, которым он нужен для автофлаша.
        //
        // Гонку с флашем это не открывает: маркер уже лежит в Head, а флаш
        // переносит маркеры Head в новый блок.
        for (block in blocks) {
            block.addTombstones(block.offsetsOf(matchers).map { Tombstone(it, range) })
        }
    }

    /**
     * Только для демо: из каких источников эта серия отдаётся сейчас.
     * HEAD, BLOCK или оба. Без этой пометки на защите невозможно показать,
     * что merge действительно работает, а не что всё лежит в памяти.
     */
    internal fun sourceTagsOf(labels: Labels, range: TimeRange): List<String> =
        dbLock.readLock().withLock {
            val matchers = labels.map { Matcher.Equals(it.name, it.value) }
            val tags = ArrayList<String>(2)
            if (hasAny(head, range, matchers)) tags.add("HEAD")
            if (storage.overlapping(range).any { hasAny(it, range, matchers) }) tags.add("BLOCK")
            tags
        }

    private fun hasAny(source: SeriesSource, range: TimeRange, matchers: List<Matcher>): Boolean =
        source.select(range, matchers).use { it.next() }

    override fun flush() {
        dbLock.writeLock().withLock { flushLocked(Long.MAX_VALUE) }
    }

    /** Вызывается только под dbLock.writeLock. */
    private fun flushLocked(upTo: Long) {
        val snapshot = head.truncate(upTo) ?: return
        storage.write(snapshot)
    }

    /**
     * Флашит Head. Без WAL это единственный способ не потерять свежие данные
     * при штатном завершении: при kill -9 несброшенный Head теряется.
     */
    override fun close() {
        dbLock.writeLock().withLock {
            flushLocked(Long.MAX_VALUE)
            head.close()
            storage.close()
        }
    }

    /**
     * Порог считается по таймстемпам сэмплов, не по стенным часам.
     *
     * Обе арифметические операции могут переполниться на краях диапазона
     * Long, поэтому идут через *Exact: span между Long.MIN_VALUE и
     * Long.MAX_VALUE в Long не влезает, а minTime + headWindow уходит за
     * край, если minTime близко к Long.MAX_VALUE.
     */
    private fun maybeAutoFlush() {
        dbLock.writeLock().withLock {
            if (head.isEmpty) return

            val range = head.range
            val span = try {
            Math.subtractExact(range.maxTime, range.minTime)
            } catch (overflow: ArithmeticException) {
                Long.MAX_VALUE
            }
            if (span <= config.headWindow) return

            val bound = try {
                Math.addExact(range.minTime, config.headWindow)
            } catch (overflow: ArithmeticException) {
                Long.MAX_VALUE
            }

            flushLocked(bound)
        }
    }
}
