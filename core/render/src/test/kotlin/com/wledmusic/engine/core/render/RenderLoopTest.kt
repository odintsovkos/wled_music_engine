package com.wledmusic.engine.core.render

import com.wledmusic.engine.core.dsp.AudioFeatures
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderLoopTest {
    @Test
    fun rendersAtFixedRateWithoutNewFeatures() = runTest {
        var frames = 0
        val settings = MutableStateFlow(RenderSettings(EffectId.AMBIENT_GLOW))
        val loop = RenderLoop(LedLayout.Strip(30), settings, { _, n -> assertEquals(30, n); frames++ }, fps = 50,
            clockNanos = { currentTime * 1_000_000 })
        val features = MutableStateFlow<AudioFeatures?>(Frames.of(level = 0.5f))
        val job = launch { loop.run(features) }
        advanceTimeBy(1_000)
        job.cancelAndJoin()
        assertTrue("frames $frames", frames in 48..51)
    }

    @Test
    fun settingsChangeAppliesOnNextFrame() = runTest {
        val out = ArrayList<ByteArray>()
        val settings = MutableStateFlow(RenderSettings(EffectId.AMBIENT_GLOW, EffectParams(brightness = 100)))
        val loop = RenderLoop(LedLayout.Strip(10), settings, { rgb, _ -> out += rgb.copyOf() },
            clockNanos = { currentTime * 1_000_000 })
        val job = launch { loop.run(MutableStateFlow(null)) }
        advanceTimeBy(100)
        settings.value = settings.value.copy(params = EffectParams(brightness = 0))
        advanceTimeBy(40)
        job.cancelAndJoin()
        assertTrue(out.first().any { it.toInt() != 0 })
        assertTrue(out.last().all { it.toInt() == 0 })
    }
}
