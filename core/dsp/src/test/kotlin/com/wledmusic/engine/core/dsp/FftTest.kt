package com.wledmusic.engine.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class FftTest {
    @Test
    fun sinePeaksAtExpectedBin() {
        val fft = Fft(2048)
        val signal = Signals.sine(1_000f, 2048f / 48_000, amplitude = 0.5f)
        val mags = FloatArray(fft.binCount)
        fft.magnitudes(signal, mags)
        val peakBin = mags.indices.maxBy { mags[it] }
        val expected = (1_000f / (48_000f / 2048)).let { kotlin.math.round(it).toInt() }
        assertEquals(expected, peakBin)
        // Амплитуда с окном Ханна для синусоиды вне центра бина — в пределах ~1.5 дБ от A.
        assertEquals(0.5f, mags[peakBin], 0.08f)
    }

    @Test
    fun matchesNaiveDft() {
        val n = 256
        val fft = Fft(n)
        val random = Random(7)
        val input = FloatArray(n) { random.nextFloat() * 2f - 1f }
        val mags = FloatArray(fft.binCount)
        fft.magnitudes(input, mags)

        val scale = 2.0 / fft.window.sum()
        for (k in 0 until fft.binCount) {
            var re = 0.0
            var im = 0.0
            for (t in 0 until n) {
                val x = input[t] * fft.window[t]
                re += x * cos(2 * PI * k * t / n)
                im -= x * sin(2 * PI * k * t / n)
            }
            var expected = sqrt(re * re + im * im) * scale
            if (k == 0 || k == n / 2) expected *= 0.5
            assertEquals("bin $k", expected, mags[k].toDouble(), 1e-3)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPowerOfTwo() {
        Fft(1000)
    }

    @Test
    fun silenceGivesZeroSpectrum() {
        val fft = Fft(512)
        val mags = FloatArray(fft.binCount)
        fft.magnitudes(FloatArray(512), mags)
        assertEquals(0f, mags.maxOf { abs(it) }, 0f)
    }
}
