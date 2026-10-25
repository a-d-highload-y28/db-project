package org.pirsbd.tsdb.storage.codec

import org.pirsbd.tsdb.api.Sample
import org.pirsbd.tsdb.chunks.ChunkEncoding
import org.pirsbd.tsdb.chunks.ChunkRef
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * chunks/000001:
 *   magic:int32, version:int8, padding:3 байта,
 *   затем на чанк: len:int32, encoding:int8, data, crc32:int32
 *
 * len это длина только data, без байта encoding и без crc32: зная её,
 * читатель перепрыгивает чанк не разбирая его.
 * CRC считается по encoding + data.
 * data = count:int32, count × (ts:int64, value:float64)
 */
internal object ChunkFileCodec {
    const val MAGIC: Int = 0x43484E4B
    const val VERSION: Int = 1

    fun chunkRef(fileNumber: Int, offset: Long): ChunkRef =
        ChunkRef((fileNumber.toLong() shl 32) or (offset and 0xFFFFFFFFL))

    fun fileNumberOf(ref: ChunkRef): Int = (ref.value ushr 32).toInt()

    fun offsetOf(ref: ChunkRef): Int = (ref.value and 0xFFFFFFFFL).toInt()

    fun write(out: OutputStream, chunks: List<List<Sample>>, fileNumber: Int = 1): List<ChunkRef> {
        val sink = CountingOutput(out)
        sink.writeInt(MAGIC)
        sink.writeByte(VERSION)
        sink.writeBytes(ByteArray(3))

        val refs = ArrayList<ChunkRef>(chunks.size)
        for (samples in chunks) {
            refs.add(chunkRef(fileNumber, sink.position))

            val buffer = ByteArrayOutputStream()
            val body = CountingOutput(buffer)
            body.writeInt(samples.size)
            for (sample in samples) {
                body.writeLong(sample.timestamp)
                body.writeDouble(sample.value)
            }
            val payload = buffer.toByteArray()

            sink.writeInt(payload.size)
            sink.writeByte(ChunkEncoding.RAW.id.toInt())
            sink.writeBytes(payload)

            val crcInput = ByteArray(1 + payload.size)
            crcInput[0] = ChunkEncoding.RAW.id
            payload.copyInto(crcInput, 1)
            sink.writeInt(crc32(crcInput, 0, crcInput.size))
        }
        sink.flush()
        return refs
    }

    fun read(bytes: ByteArray, ref: ChunkRef): List<Sample> {
        val reader = ByteReader(bytes)
        if (reader.readInt() != MAGIC) throw CorruptBlockException("bad chunks magic")
        if (reader.readByte() != VERSION) throw CorruptBlockException("bad chunks version")

        reader.seek(offsetOf(ref))
        val length = reader.readInt()
        if (length < 0) throw CorruptBlockException("negative chunk length: $length")

        val encodingAt = reader.position
        val encoding = reader.readByte().toByte()
        if (encoding != ChunkEncoding.RAW.id) {
            throw CorruptBlockException("unsupported chunk encoding: $encoding")
        }

        val dataAt = reader.position
        val count = reader.readInt()
        if (count < 0) throw CorruptBlockException("negative sample count: $count")
        val samples = ArrayList<Sample>(count)
        repeat(count) { samples.add(Sample(reader.readLong(), reader.readDouble())) }

        if (reader.position - dataAt != length) {
            throw CorruptBlockException("chunk length mismatch at ${offsetOf(ref)}")
        }
        reader.verifyCrc(encodingAt, 1 + length)
        return samples
    }
}
