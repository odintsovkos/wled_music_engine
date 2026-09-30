package com.wledmusic.engine.core.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DdpCodecTest {
    private fun frame(leds: Int) = ByteArray(leds * 3) { (it % 251).toByte() }

    private fun encodeAll(rgb: ByteArray, leds: Int, seq: Int = 1): List<ByteArray> =
        List(DdpCodec.packetCount(leds)) { i ->
            val out = ByteArray(DdpCodec.HEADER_SIZE + DdpCodec.MAX_DATA_SIZE)
            out.copyOf(DdpCodec.encodePacket(rgb, leds, i, seq, out))
        }

    @Test
    fun singlePacketFor256Leds() {
        val rgb = frame(256)
        val packets = encodeAll(rgb, 256)
        assertEquals(1, packets.size)
        val p = packets[0]
        assertEquals(10 + 768, p.size)
        assertEquals(0x41, p[0].toInt() and 0xFF)
        assertEquals(1, p[1].toInt())
        assertEquals(0x0B, p[2].toInt())
        assertEquals(0x01, p[3].toInt())
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), p.copyOfRange(4, 8))
        assertArrayEquals(byteArrayOf(0x03, 0x00), p.copyOfRange(8, 10))
        assertArrayEquals(rgb, p.copyOfRange(10, p.size))
    }

    @Test
    fun threePacketsFor1024Leds() {
        val rgb = frame(1024)
        val packets = encodeAll(rgb, 1024, seq = 7)
        assertEquals(3, packets.size)
        val headers = packets.map { DdpCodec.decodeHeader(it)!! }
        assertEquals(listOf(0, 1440, 2880), headers.map { it.offset })
        assertEquals(listOf(1440, 1440, 192), headers.map { it.length })
        assertEquals(listOf(false, false, true), headers.map { it.push })
        assertTrue(headers.all { it.sequence == 7 && it.dataType == DdpCodec.TYPE_RGB8 })
        val joined = packets.flatMap { it.copyOfRange(10, it.size).toList() }.toByteArray()
        assertArrayEquals(rgb, joined)
    }

    @Test
    fun exactlyOnePacketAt480Leds() {
        assertEquals(1, DdpCodec.packetCount(480))
        assertEquals(2, DdpCodec.packetCount(481))
        assertEquals(10 + 3, DdpCodec.packetSize(481, 1))
    }

    @Test
    fun decodeRejectsWrongLengthOrVersion() {
        val p = encodeAll(frame(10), 10)[0]
        assertNull(DdpCodec.decodeHeader(p, p.size - 1))
        val v2 = p.copyOf().also { it[0] = 0x81.toByte() }
        assertNull(DdpCodec.decodeHeader(v2))
        assertFalse(DdpCodec.decodeHeader(encodeAll(frame(600), 600)[0])!!.push)
    }

    @Test(expected = IllegalArgumentException::class)
    fun sequenceMustBe1To15() {
        DdpCodec.encodePacket(frame(1), 1, 0, 0, ByteArray(20))
    }
}
