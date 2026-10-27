package org.pirsbd.tsdb.storage.codec

import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Файл блока не прошёл проверку: magic, version, длина секции или CRC32. */
class CorruptBlockException(message: String) : IOException(message)

internal fun crc32(bytes: ByteArray, from: Int, length: Int): Int {
    val crc = CRC32()
    crc.update(bytes, from, length)
    return crc.value.toInt()
}

/**
 * Пишет big-endian фиксированной ширины и считает позицию: оффсеты секций
 * известны только по ходу записи, их надо запоминать для TOC.
 */
internal class CountingOutput(private val out: OutputStream) {
    var position: Long = 0
        private set

    fun writeByte(value: Int) {
        out.write(value)
        position += 1
    }

    fun writeInt(value: Int) {
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array())
        position += 4
    }

    fun writeLong(value: Long) {
        out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array())
        position += 8
    }

    fun writeDouble(value: Double) {
        out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(value).array())
        position += 8
    }

    fun writeBytes(bytes: ByteArray) {
        out.write(bytes)
        position += bytes.size
    }

    fun writeUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        writeBytes(bytes)
    }

    fun flush() = out.flush()
}

/** Читает big-endian из массива, проверяя границы перед каждым чтением. */
internal class ByteReader(private val bytes: ByteArray) {
    var position: Int = 0
        private set

    fun seek(to: Int) {
        if (to < 0 || to > bytes.size) {
            throw CorruptBlockException("seek out of bounds: $to, size ${bytes.size}")
        }
        position = to
    }

    private fun requireBytes(length: Int) {
        if (length < 0 || position + length > bytes.size) {
            throw CorruptBlockException("truncated: need $length at $position, size ${bytes.size}")
        }
    }

    fun readByte(): Int {
        requireBytes(1)
        return bytes[position++].toInt()
    }

    fun readInt(): Int {
        requireBytes(4)
        val value = ByteBuffer.wrap(bytes, position, 4).order(ByteOrder.BIG_ENDIAN).int
        position += 4
        return value
    }

    fun readLong(): Long {
        requireBytes(8)
        val value = ByteBuffer.wrap(bytes, position, 8).order(ByteOrder.BIG_ENDIAN).long
        position += 8
        return value
    }

    fun readDouble(): Double {
        requireBytes(8)
        val value = ByteBuffer.wrap(bytes, position, 8).order(ByteOrder.BIG_ENDIAN).double
        position += 8
        return value
    }

    fun readUtf8(): String {
        val length = readInt()
        if (length < 0) throw CorruptBlockException("negative string length: $length")
        requireBytes(length)
        val value = String(bytes, position, length, Charsets.UTF_8)
        position += length
        return value
    }

    /** Сверяет CRC32 диапазона с int32, который лежит на текущей позиции. */
    fun verifyCrc(from: Int, length: Int) {
        if (from < 0 || length < 0 || from + length > bytes.size) {
            throw CorruptBlockException("crc range out of bounds: $from +$length, size ${bytes.size}")
        }
        val expected = crc32(bytes, from, length)
        val actual = readInt()
        if (expected != actual) {
            throw CorruptBlockException("crc mismatch at $from: expected $expected, got $actual")
        }
    }
}
