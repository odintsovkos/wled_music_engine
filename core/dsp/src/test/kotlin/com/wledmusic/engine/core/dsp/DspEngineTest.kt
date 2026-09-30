package com.wledmusic.engine.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DspEngineTest {
    private val sr = 48_000

    @Test
    fun defaultConfigMatchesSpec() {
        val c = DspConfig()
        assertEquals(48_000, c.sampleRate)
        assertEquals(16, c.bandCount)
        assertEquals(20f..250f, c.bassRange)
        assertEquals(250f..4_000f, c.midRange)
        assertEquals(4_000f..16_000f, c.highRange)
        assertEquals(21.33f, c.frameMs, 0.01f)
    }

    @Test
    fun tone1kHzPeaksInChannelContaining1kHz() {
        val engine = DspEngine()
        val frames = Signals.run(engine, Signals.sine(1_000f, 1f))
        val last = frames.last()
        val ranges = SpectrumBands.logRanges(16, 43f, 9_000f)
        val expected = ranges.indexOfFirst { 1_000f in it }
        assertEquals(expected, last.bands.indices.maxBy { last.bands[it] })
        assertEquals(1_000f, last.majorPeakHz, 5f)
    }

    @Test
    fun lowToneLandsInLowestChannels() {
        val engine = DspEngine()
        val last = Signals.run(engine, Signals.sine(60f, 1f)).last()
        val maxChannel = last.bands.indices.maxBy { last.bands[it] }
        assertTrue("channel $maxChannel", maxChannel <= 1)
        assertEquals(60f, last.majorPeakHz, 10f)
    }

    @Test
    fun highToneRaisesHighBand() {
        val engine = DspEngine()
        val last = Signals.run(engine, Signals.sine(8_000f, 1f)).last()
        assertTrue("high=${last.high} bass=${last.bass}", last.high > last.bass)
        assertTrue("high=${last.high} mid=${last.mid}", last.high > last.mid)
    }

    @Test
    fun quietNoiseIsGatedToZero() {
        val engine = DspEngine()
        val frames = Signals.run(engine, Signals.whiteNoise(5f, rmsDb = -70f))
        for (f in frames) {
            assertFalse(f.gateOpen)
            assertEquals(0f, f.level, 0f)
            assertEquals(0f, f.rawLevel, 0f)
            f.bands.forEach { assertEquals(0f, it, 0f) }
            assertEquals(0f, f.bass + f.mid + f.high, 0f)
            assertFalse(f.peak)
        }
    }

    @Test
    fun normalizationAdaptsToVolume() {
        fun meanLevel(gain: Float): Float {
            val frames = Signals.run(DspEngine(), Signals.musicLike(15f, gain))
            val adapted = frames.drop((5_000 / DspConfig().frameMs).toInt())
            return adapted.map { it.level }.average().toFloat()
        }
        val loud = meanLevel(1f)
        val quiet = meanLevel(0.1f)
        assertTrue("loud=$loud quiet=$quiet", abs(loud - quiet) / loud <= 0.2f)
    }

    @Test
    fun levelKeepsDynamicsOnDenseMusic() {
        // Регрессия: при нормализации к пику уровень плотной музыки прилипал к 1 и эффекты WLED не пульсировали.
        val frames = Signals.run(DspEngine(), Signals.musicLike(15f))
        val levels = frames.drop((3_000 / DspConfig().frameMs).toInt()).map { it.level }.sorted()
        fun p(q: Double) = levels[(levels.size * q).toInt()]
        assertTrue("median=${p(0.5)}", p(0.5) in 0.25f..0.7f)
        assertTrue("p10=${p(0.1)} p90=${p(0.9)}", p(0.9) - p(0.1) >= 0.25f)
        val saturated = levels.count { it > 0.98f }.toFloat() / levels.size
        assertTrue("saturated=$saturated", saturated < 0.1f)
    }

    @Test
    fun releaseIsSmoothAndBounded() {
        val config = DspConfig(releaseMs = 300f)
        val engine = DspEngine(config)
        val loud = Signals.run(engine, Signals.sine(200f, 2f)).last().level
        val after = Signals.run(engine, Signals.silence(2f)).map { it.level }

        var prev = loud
        for (v in after) {
            assertTrue("level must not rise during release", v <= prev + 1e-6f)
            prev = v
        }
        val firstBelow = after.indexOfFirst { it < 0.1f * loud }
        val ms = (firstBelow + 1) * config.frameMs
        assertTrue("fell below 10% after $ms ms", ms in 150f..1_000f)
    }

    @Test
    fun metronomeClicksAreDetected() {
        val engine = DspEngine()
        val onsets = Signals.run(engine, Signals.clicks(2f, 10f)).count { it.peak }
        assertTrue("onsets=$onsets", onsets in 18..21)
    }

    @Test
    fun stationaryToneHasNoOnsetsAfterFirstSecond() {
        val engine = DspEngine()
        val frames = Signals.run(engine, Signals.sine(440f, 10f))
        val skip = (1_000 / engine.config.frameMs).toInt() + 1
        assertEquals(0, frames.drop(skip).count { it.peak })
    }

    @Test
    fun onsetsRespectRefractoryInterval() {
        val engine = DspEngine()
        val frames = Signals.run(engine, Signals.musicLike(10f))
        val peaks = frames.indices.filter { frames[it].peak }
        val minFrames = kotlin.math.ceil(100f / engine.config.frameMs).toInt()
        peaks.zipWithNext().forEach { (a, b) -> assertTrue("gap ${b - a}", b - a >= minFrames) }
    }

    @Test
    fun outputsAreWithinUnitRange() {
        val frames = Signals.run(DspEngine(), Signals.musicLike(5f, gain = 2f))
        for (f in frames) {
            listOf(f.level, f.rawLevel, f.bass, f.mid, f.high).forEach { assertTrue(it in 0f..1f) }
            f.bands.forEach { assertTrue(it in 0f..1f) }
        }
    }

    @Test
    fun resetClearsState() {
        val engine = DspEngine()
        Signals.run(engine, Signals.sine(200f, 1f))
        engine.reset()
        val f = engine.process(FloatArray(engine.config.hopSize))
        assertEquals(0f, f.level, 0f)
        assertFalse(f.gateOpen)
    }
}
