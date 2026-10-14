package org.pirsbd.tsdb.storage.codec

import org.pirsbd.tsdb.api.TimeRange
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TombstoneFileCodecTest {

    private fun roundTrip(tombstones: List<Tombstone>): List<Tombstone> {
        val out = ByteArrayOutputStream()
        TombstoneFileCodec.write(out, tombstones)
        return TombstoneFileCodec.read(out.toByteArray())
    }

    @Test
    fun `tombstones round trip`() {
        val tombstones = listOf(
            Tombstone(8, TimeRange(10, 20)),
            Tombstone(8, TimeRange(40, 50)),
            Tombstone(64, TimeRange(0, 5)),
        )

        assertEquals(tombstones, roundTrip(tombstones))
    }

    @Test
    fun `an empty tombstone file round trips`() {
        assertEquals(emptyList(), roundTrip(emptyList()))
    }

    @Test
    fun `extreme ranges round trip`() {
        val tombstones = listOf(Tombstone(0, TimeRange(Long.MIN_VALUE, Long.MAX_VALUE)))

        assertEquals(tombstones, roundTrip(tombstones))
    }

    @Test
    fun `a flipped byte is caught by the crc`() {
        val out = ByteArrayOutputStream()
        TombstoneFileCodec.write(out, listOf(Tombstone(8, TimeRange(10, 20))))
        val bytes = out.toByteArray()
        bytes[bytes.size - 6] = (bytes[bytes.size - 6] + 1).toByte()

        assertFailsWith<CorruptBlockException> { TombstoneFileCodec.read(bytes) }
    }

    @Test
    fun `a wrong magic is rejected`() {
        val out = ByteArrayOutputStream()
        TombstoneFileCodec.write(out, emptyList())
        val bytes = out.toByteArray()
        bytes[0] = 0

        assertFailsWith<CorruptBlockException> { TombstoneFileCodec.read(bytes) }
    }
}
