package org.pirsbd.tsdb.storage.codec

import org.pirsbd.tsdb.api.TimeRange
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Маркер удаления: для серии по её оффсету в index диапазон считается удалённым. */
internal data class Tombstone(val seriesOffset: Int, val range: TimeRange)

/**
 * tombstones:
 *   magic:int32, version:int8, count:int32,
 *   count × (seriesRef:int64, mint:int64, maxt:int64), crc32:int32
 *
 * Поля count в спеке форматов нет. Без него читателю пришлось бы выводить
 * число записей из длины файла, что ломается при изменении размера записи.
 *
 * Единственный мутабельный файл блока: delete по уже сброшенным данным
 * перезаписывает его целиком через .tmp и rename.
 */
internal object TombstoneFileCodec {
    const val MAGIC: Int = 0x0130BA30
    const val VERSION: Int = 1

    fun write(out: OutputStream, tombstones: List<Tombstone>) {
        val sink = CountingOutput(out)
        sink.writeInt(MAGIC)
        sink.writeByte(VERSION)

        val buffer = ByteArrayOutputStream()
        val body = CountingOutput(buffer)
        body.writeInt(tombstones.size)
        for (tombstone in tombstones) {
            body.writeLong(tombstone.seriesOffset.toLong())
            body.writeLong(tombstone.range.minTime)
            body.writeLong(tombstone.range.maxTime)
        }
        val payload = buffer.toByteArray()

        sink.writeBytes(payload)
        sink.writeInt(crc32(payload, 0, payload.size))
        sink.flush()
    }

    fun read(bytes: ByteArray): List<Tombstone> {
        val reader = ByteReader(bytes)
        if (reader.readInt() != MAGIC) throw CorruptBlockException("bad tombstones magic")
        if (reader.readByte() != VERSION) throw CorruptBlockException("bad tombstones version")

        val at = reader.position
        val count = reader.readInt()
        if (count < 0) throw CorruptBlockException("negative tombstone count: $count")
        val tombstones = ArrayList<Tombstone>(count)
        repeat(count) {
            val seriesOffset = reader.readLong().toInt()
            val minTime = reader.readLong()
            val maxTime = reader.readLong()
            tombstones.add(Tombstone(seriesOffset, TimeRange(minTime, maxTime)))
        }
        reader.verifyCrc(at, reader.position - at)
        return tombstones
    }
}
