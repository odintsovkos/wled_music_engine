package com.wledmusic.engine.core.render

import com.wledmusic.engine.core.dsp.AudioFeatures
import com.wledmusic.engine.core.render.effects.Ripple
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory

class EffectsTest {
    private val strip = LedLayout.Strip(60)
    private val matrix = LedLayout.Matrix(16, 16)

    /** Прогон эффекта: [frameAt] выдаёт признаки для момента t (мс), 50 к/с. */
    private fun run(
        layout: LedLayout,
        settings: RenderSettings,
        seconds: Float,
        renderer: Renderer = Renderer(layout),
        startMs: Long = 0,
        onFrame: (tMs: Long, out: ByteArray) -> Unit = { _, _ -> },
        frameAt: (tMs: Long) -> AudioFeatures?,
    ): Renderer {
        val frames = (seconds * 50).toInt()
        for (i in 0 until frames) {
            val t = startMs + i * 20L
            onFrame(t, renderer.render(frameAt(t), t, 20f, settings))
        }
        return renderer
    }

    private fun lit(rgb: ByteArray) = (0 until rgb.size / 3).count {
        (rgb[it * 3].toInt() and 0xFF) + (rgb[it * 3 + 1].toInt() and 0xFF) + (rgb[it * 3 + 2].toInt() and 0xFF) > 30
    }

    @Test
    fun everyEffectRendersOnStripAndMatrix() {
        for (layout in listOf(strip, matrix)) for (id in EffectId.values()) {
            var maxLit = 0
            var beat = 0
            run(layout, RenderSettings(id, direction = AnimationDirection.UP), 3f, onFrame = { _, out -> maxLit = maxOf(maxLit, lit(out)) }) { t ->
                val phase = (t % 500) / 500f
                Frames.of(level = 0.3f + 0.6f * (1 - phase), peak = t % 500 == 0L && beat++ >= 0, hz = 100f + t % 2000)
            }
            assertTrue("$id on ${layout.javaClass.simpleName} lit $maxLit", maxLit > 0)
        }
    }

    @Test
    fun vuPulseGrowsMonotonically() {
        for (layout in listOf(strip, matrix)) {
            val counts = (2..8).map { step ->
                val level = step / 10f
                val r = run(layout, RenderSettings(EffectId.VU_PULSE), 0.5f) { Frames.of(level = level) }
                lit(r.output)
            }
            for (i in 1 until counts.size) assertTrue("$counts", counts[i] >= counts[i - 1])
            assertTrue("$counts", counts.last() > counts.first())
        }
    }

    @Test
    fun spectrumBassLightsLeftColumnsOnMatrix() {
        val bands = FloatArray(16) { if (it < 3) 1f else 0f }
        val r = run(matrix, RenderSettings(EffectId.SPECTRUM, direction = AnimationDirection.UP), 0.5f) { Frames.of(bands = bands) }
        val canvas = r.canvas
        val leftBottom = canvas.get(0, 15)
        val rightBottom = canvas.get(15, 15)
        assertTrue(leftBottom != 0)
        assertEquals(0, rightBottom)
    }

    @Test
    fun spectrumBassLightsCenterOnStrip() {
        val bands = FloatArray(16) { if (it < 2) 1f else 0f }
        val r = run(strip, RenderSettings(EffectId.SPECTRUM), 0.3f) { Frames.of(bands = bands) }
        assertTrue(r.canvas.get(30, 0) != 0)
        assertEquals(0, r.canvas.get(0, 0))
        assertEquals(0, r.canvas.get(59, 0))
    }

    @Test
    fun rippleKeepsAtMostEightWaves() {
        val canvas = Canvas(matrix)
        val ripple = Ripple(canvas)
        val ctx = FrameContext(Sound(), FlashLimiter()).apply { dtMs = 20f; params = EffectId.RIPPLE.defaultParams() }
        for (i in 0 until 20) {
            ctx.sound.update(Frames.of(peak = true), 50, 20f)
            ctx.nowMs = i * 20L
            ripple.render(ctx)
        }
        assertEquals(Ripple.MAX_WAVES, ripple.activeWaves)
    }

    @Test
    fun beatFlashAtSixBeatsPerSecondFlashesAtMostThreeTimes() {
        for (id in listOf(EffectId.BEAT_FLASH, EffectId.RIPPLE, EffectId.VU_PULSE)) {
            val flashes = ArrayList<Long>()
            var lastLuma = 0
            val settings = RenderSettings(id, id.defaultParams().copy(intensity = 100, brightness = 100, sensitivity = 100))
            run(matrix, settings, 5f, onFrame = { t, out ->
                val l = Renderer.luma(out)
                if (l - lastLuma > Renderer.FLASH_DELTA) flashes += t
                lastLuma = l
            }) { t ->
                // Акцент каждые ~166 мс, уровень скачет от тишины к максимуму.
                val beat = t % 160 == 0L
                Frames.of(level = if (t % 160 < 40) 1f else 0.05f, peak = beat)
            }
            for (t in flashes) assertTrue("$id: $flashes", flashes.count { it in t until t + 1000 } <= 3)
        }
    }

    @Test
    fun effectsSettleWithinOneSecondOfSilence() {
        for (id in EffectId.values().filter { it != EffectId.AMBIENT_GLOW }) {
            val r = Renderer(matrix)
            val s = RenderSettings(id, direction = AnimationDirection.UP)
            run(matrix, s, 2f, r) { t -> Frames.of(level = 0.8f, peak = t % 500 == 0L) }
            run(matrix, s, 1f, r, startMs = 2_000) { Frames.silent }
            val luma = Renderer.luma(r.output)
            assertTrue("$id luma $luma", luma <= 3)
        }
    }

    @Test
    fun ambientGlowStaysVisibleInSilence() {
        val r = run(matrix, RenderSettings(EffectId.AMBIENT_GLOW), 2f) { Frames.silent }
        assertTrue(lit(r.output) > 0)
    }

    @Test
    fun brightnessScalesOutput() {
        val full = run(strip, RenderSettings(EffectId.AMBIENT_GLOW, EffectId.AMBIENT_GLOW.defaultParams().copy(brightness = 100)), 0.2f) { Frames.silent }.output.copyOf()
        val half = run(strip, RenderSettings(EffectId.AMBIENT_GLOW, EffectId.AMBIENT_GLOW.defaultParams().copy(brightness = 50)), 0.2f) { Frames.silent }.output
        for (i in full.indices) {
            val expected = (full[i].toInt() and 0xFF) / 2
            assertTrue(kotlin.math.abs((half[i].toInt() and 0xFF) - expected) <= 1)
        }
    }

    @Test
    fun flowWaterfallMovesAlongDirection() {
        val bands = FloatArray(16) { 1f }
        val r = run(matrix, RenderSettings(EffectId.FLOW, direction = AnimationDirection.UP), 0.2f) { Frames.of(bands = bands) }
        // Направление ↑: новые строки появляются снизу.
        assertTrue(r.canvas.get(8, 15) != 0)
        assertEquals(0, r.canvas.get(8, 0))
    }

    @Test
    fun renderingIsFastAndAllocationFree() {
        val big = LedLayout.Matrix(32, 32)
        val features = Frames.of(level = 0.7f, peak = true)
        for (id in EffectId.values()) {
            val r = Renderer(big)
            val s = RenderSettings(id)
            repeat(500) { r.render(features, it * 20L, 20f, s) } // прогрев JIT
            val start = System.nanoTime()
            repeat(500) { r.render(features, 10_000 + it * 20L, 20f, s) }
            val avgMs = (System.nanoTime() - start) / 1e6 / 500
            println("$id 1024 LED: %.3f ms/frame".format(avgMs))
            assertTrue("$id avg $avgMs ms", avgMs < 2.0)

            val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: continue
            if (!bean.isThreadAllocatedMemorySupported) continue
            val tid = Thread.currentThread().id
            val before = bean.getThreadAllocatedBytes(tid)
            repeat(200) { r.render(features, 20_000 + it * 20L, 20f, s) }
            val perFrame = (bean.getThreadAllocatedBytes(tid) - before) / 200
            assertTrue("$id allocated $perFrame bytes/frame", perFrame < 64)
        }
    }
}
