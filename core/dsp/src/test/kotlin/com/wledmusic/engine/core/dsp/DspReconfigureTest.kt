package com.wledmusic.engine.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DspReconfigureTest {
    @Test
    fun releaseChangeHasNoLevelJump() {
        val engine = DspEngine()
        val before = Signals.run(engine, Signals.sine(200f, 2f)).last().level
        engine.reconfigure(engine.config.copy(releaseMs = 180f))
        val after = Signals.run(engine, Signals.sine(200f, 0.1f))
        assertEquals(180f, engine.config.releaseMs)
        assertTrue("before=$before after=${after.first().level}", abs(after.first().level - before) < 0.05f)
    }

    @Test
    fun shorterReleaseFallsFaster() {
        fun fallFrames(releaseMs: Float): Int {
            val engine = DspEngine()
            Signals.run(engine, Signals.sine(200f, 2f))
            engine.reconfigure(engine.config.copy(releaseMs = releaseMs))
            val levels = Signals.run(engine, Signals.silence(2f)).map { it.level }
            return levels.indexOfFirst { it < 0.05f }
        }
        assertTrue(fallFrames(100f) < fallFrames(600f))
    }

    @Test
    fun noiseGateChangeAppliesOnNextFrame() {
        val engine = DspEngine()
        val noise = Signals.whiteNoise(1f, rmsDb = -55f)
        assertTrue(Signals.run(engine, noise).last().gateOpen)
        engine.reconfigure(engine.config.copy(noiseGateDb = -52f))
        assertFalse(Signals.run(engine, Signals.whiteNoise(0.1f, rmsDb = -55f, seed = 2)).last().gateOpen)
    }

    @Test
    fun beatDetectionOffSuppressesPeaks() {
        val engine = DspEngine(DspConfig(beatDetection = false))
        assertEquals(0, Signals.run(engine, Signals.clicks(2f, 5f)).count { it.peak })
        engine.reconfigure(engine.config.copy(beatDetection = true))
        assertTrue(Signals.run(engine, Signals.clicks(2f, 5f)).count { it.peak } >= 8)
    }

    @Test
    fun autoGainOffUsesFixedReference() {
        fun meanLevel(autoGain: Boolean): Float {
            val frames = Signals.run(DspEngine(DspConfig(autoGain = autoGain)), Signals.whiteNoise(6f, rmsDb = -40f))
            return frames.drop(150).map { it.level }.average().toFloat()
        }
        val agc = meanLevel(true)
        val fixed = meanLevel(false)
        assertEquals(0.1f, fixed, 0.02f) // −40 dBFS при опоре −20 dBFS = 0.1
        assertTrue("agc=$agc fixed=$fixed", agc > fixed * 2)
    }

    @Test
    fun fixedReferenceMapsMinus20DbfsToOne() {
        val engine = DspEngine(DspConfig(autoGain = false))
        val frames = Signals.run(engine, Signals.whiteNoise(2f, rmsDb = -20f))
        assertEquals(1f, frames.last().rawLevel, 0.05f)
    }

    @Test
    fun bandBoundsChangeLive() {
        val engine = DspEngine()
        Signals.run(engine, Signals.sine(200f, 1f))
        // 200 Гц было басом; после сдвига границы до 180 Гц это середина.
        engine.reconfigure(engine.config.copy(bassRange = 20f..180f, midRange = 180f..2_500f, highRange = 2_500f..16_000f))
        val last = Signals.run(engine, Signals.sine(200f, 2f)).last()
        assertTrue("bass=${last.bass} mid=${last.mid}", last.mid > last.bass)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonAdjacentBandsRejected() {
        DspConfig(bassRange = 20f..300f, midRange = 250f..4_000f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun fftSizeCannotChangeLive() {
        val engine = DspEngine()
        engine.reconfigure(DspConfig(fftSize = 4096))
    }
}
