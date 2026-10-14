package org.pirsbd.tsdb.api.impl

import org.pirsbd.tsdb.api.Appender
import org.pirsbd.tsdb.api.Matcher
import org.pirsbd.tsdb.api.Querier
import org.pirsbd.tsdb.api.SeriesSource
import org.pirsbd.tsdb.api.TSDB
import org.pirsbd.tsdb.api.TSDBConfig
import org.pirsbd.tsdb.api.TimeRange
import org.pirsbd.tsdb.head.MemoryHead
import org.pirsbd.tsdb.storage.FileStorage
import org.pirsbd.tsdb.storage.codec.Tombstone
import java.nio.file.Path

class DefaultTSDB(
    dir: Path,
    private val config: TSDBConfig = TSDBConfig(),
) : TSDB {

    private val head = MemoryHead(config.stripeSize)
    private val storage = FileStorage(dir)

    override fun appender(): Appender = HeadAppender(head) { maybeAutoFlush() }

    /** Head идёт первым: при равном таймстемпе он должен перебить блок. */
    override fun querier(range: TimeRange): Querier {
        val sources = ArrayList<SeriesSource>()
        if (head.range.overlaps(range)) sources.add(head)
        sources.addAll(storage.overlapping(range))
        return DefaultQuerier(range, sources)
    }

    override fun delete(range: TimeRange, matchers: List<Matcher>) {
        head.delete(range, matchers)
        for (block in storage.overlapping(range)) {
            block.addTombstones(block.offsetsOf(matchers).map { Tombstone(it, range) })
        }
    }

    override fun flush() {
        val snapshot = head.truncate(Long.MAX_VALUE) ?: return
        storage.write(snapshot)
    }

    /**
     * Флашит Head. Без WAL это единственный способ не потерять свежие данные
     * при штатном завершении: при kill -9 несброшенный Head теряется.
     */
    override fun close() {
        flush()
        head.close()
        storage.close()
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
        val range = head.range
        if (range == TimeRange.EMPTY) return

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

        val snapshot = head.truncate(bound) ?: return
        storage.write(snapshot)
    }
}
