package com.wledmusic.engine.core.network

import com.wledmusic.engine.core.dsp.AudioFeatures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSyncCodecTest {
    private fun features(peak: Boolean = true) = AudioFeatures(
        rms = 0.3f, rawLevel = 0.8f, level = 0.5f, bass = 0.9f, mid = 0.4f, high = 0.1f,
        bands = FloatArray(16) { it / 15f }, peak = peak, majorPeakHz = 440f,
        majorPeakMagnitude = 0.2f, majorPeakLevel = 0.5f, zeroCrossings = 40, gateOpen = true,
    )

    @Test
    fun packetHasWledLayout() {
        val bytes = AudioSyncCodec.encode(features())
        assertEquals(44, bytes.size)
        assertArrayEquals("00002".toByteArray() + 0, bytes.copyOfRange(0, 6))
        assertEquals(1, bytes[16].toInt())
        assertEquals(0, bytes[18].toInt())
        assertEquals(254, bytes[33].toInt() and 0xFF)
        // Зарезервированные байты нулевые.
        listOf(6, 7, 17, 34, 35).forEach { assertEquals("byte $it", 0, bytes[it].toInt()) }
    }

    @Test
    fun roundTripPreservesValues() {
        val f = features()
        val decoded = AudioSyncCodec.decode(AudioSyncCodec.encode(f))!!
        assertEquals(0.8f * 255f, decoded.sampleRaw, 1e-4f)
        assertEquals(0.5f * 255f, decoded.sampleSmth, 1e-4f)
        assertTrue(decoded.samplePeak)
        assertArrayEquals(IntArray(16) { (it / 15f * 254f + 0.5f).toInt() }, decoded.fftResult)
        assertEquals(510f, decoded.fftMagnitude, 1e-3f)
        assertEquals(440f, decoded.fftMajorPeak, 1e-3f)
    }

    @Test
    fun littleEndianFloats() {
        val bytes = AudioSyncCodec.encode(features())
        val bits = (bytes[40].toInt() and 0xFF) or ((bytes[41].toInt() and 0xFF) shl 8) or
            ((bytes[42].toInt() and 0xFF) shl 16) or ((bytes[43].toInt() and 0xFF) shl 24)
        assertEquals(440f, Float.fromBits(bits), 0f)
    }

    @Test
    fun silentFrameIsValidForWled() {
        val decoded = AudioSyncCodec.decode(AudioSyncCodec.encode(AudioFeatures.silent()))!!
        assertEquals(0f, decoded.sampleSmth, 0f)
        assertEquals(1f, decoded.fftMajorPeak, 0f)
    }

    @Test
    fun rejectsWrongLengthOrHeader() {
        val good = AudioSyncCodec.encode(features())
        assertNull(AudioSyncCodec.decode(good.copyOf(43)))
        assertNull(AudioSyncCodec.decode(good.copyOf(88)))
        val badHeader = good.copyOf().also { it[4] = '1'.code.toByte() }
        assertNull(AudioSyncCodec.decode(badHeader))
    }
}
