package org.pirsbd.tsdb.storage.codec

import org.pirsbd.tsdb.api.Sample
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChunkFileCodecTest {

    private fun roundTrip(chunks: List<List<Sample>>): List<Sample> {
        val out = ByteArrayOutputStream()
        val refs = ChunkFileCodec.write(out, chunks)
        return ChunkFileCodec.read(out.toByteArray(), refs[0])
    }

    @Test
    fun `a single chunk round trips`() {
        val samples = listOf(Sample(10, 1.5), Sample(20, 2.5), Sample(30, -3.0))

        assertEquals(samples, roundTrip(listOf(samples)))
    }

    @Test
    fun `several chunks each round trip through their own ref`() {
        val first = listOf(Sample(10, 1.0))
        val second = listOf(Sample(20, 2.0), Sample(30, 3.0))
        val out = ByteArrayOutputStream()

        val refs = ChunkFileCodec.write(out, listOf(first, second))
        val bytes = out.toByteArray()

        assertEquals(first, ChunkFileCodec.read(bytes, refs[0]))
        assertEquals(second, ChunkFileCodec.read(bytes, refs[1]))
    }

    @Test
    fun `extreme values survive the round trip`() {
        val samples = listOf(
            Sample(Long.MIN_VALUE, -Double.MAX_VALUE),
            Sample(0, 0.0),
            Sample(Long.MAX_VALUE, Double.MAX_VALUE),
        )

        assertEquals(samples, roundTrip(listOf(samples)))
    }

    @Test
    fun `an empty chunk round trips`() {
        assertEquals(emptyList(), roundTrip(listOf(emptyList())))
    }

    @Test
    fun `a flipped byte in the data is caught by the crc`() {
        val out = ByteArrayOutputStream()
        val refs = ChunkFileCodec.write(out, listOf(listOf(Sample(10, 1.0))))
        val bytes = out.toByteArray()
        val offset = ChunkFileCodec.offsetOf(refs[0])
        bytes[offset + 9] = (bytes[offset + 9] + 1).toByte()

        val error = assertFailsWith<CorruptBlockException> { ChunkFileCodec.read(bytes, refs[0]) }

        assertTrue(error.message!!.contains("crc"), "message was: ${error.message}")
    }

    @Test
    fun `a wrong magic is rejected`() {
        val out = ByteArrayOutputStream()
        val refs = ChunkFileCodec.write(out, listOf(listOf(Sample(10, 1.0))))
        val bytes = out.toByteArray()
        bytes[0] = 0

        assertFailsWith<CorruptBlockException> { ChunkFileCodec.read(bytes, refs[0]) }
    }

    @Test
    fun `a truncated file is rejected instead of read as garbage`() {
        val out = ByteArrayOutputStream()
        val refs = ChunkFileCodec.write(out, listOf(listOf(Sample(10, 1.0), Sample(20, 2.0))))
        val bytes = out.toByteArray()

        assertFailsWith<CorruptBlockException> {
            ChunkFileCodec.read(bytes.copyOf(bytes.size - 10), refs[0])
        }
    }

    @Test
    fun `a wrong version is rejected`() {
        val out = ByteArrayOutputStream()
        val refs = ChunkFileCodec.write(out, listOf(listOf(Sample(10, 1.0))))
        val bytes = out.toByteArray()
        bytes[4] = 99

        assertFailsWith<CorruptBlockException> { ChunkFileCodec.read(bytes, refs[0]) }
    }

    @Test
    fun `a chunk ref packs the file number and the offset`() {
        val ref = ChunkFileCodec.chunkRef(fileNumber = 7, offset = 1234L)

        assertEquals(7, ChunkFileCodec.fileNumberOf(ref))
        assertEquals(1234, ChunkFileCodec.offsetOf(ref))
    }
}
