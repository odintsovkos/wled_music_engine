package com.wledmusic.engine.core.dsp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory

class DspPerformanceTest {
    @Test
    fun frameProcessingFitsRealtimeBudget() {
        val engine = DspEngine()
        val signal = Signals.musicLike(20f)
        val hop = engine.config.hopSize
        // Прогрев JIT.
        Signals.run(engine, signal)

        val frames = signal.size / hop
        val start = System.nanoTime()
        var offset = 0
        repeat(frames) {
            engine.process(signal, offset)
            offset += hop
        }
        val avgMs = (System.nanoTime() - start) / 1e6 / frames
        println("DSP average frame time: %.3f ms".format(avgMs))
        assertTrue("avg $avgMs ms", avgMs < 2.0)
    }

    @Test
    fun hotPathAllocatesOnlyTheResult() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return
        if (!bean.isThreadAllocatedMemorySupported) return
        val engine = DspEngine()
        val signal = Signals.musicLike(5f)
        Signals.run(engine, signal)

        val hop = engine.config.hopSize
        val frames = signal.size / hop
        val tid = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(tid)
        var offset = 0
        repeat(frames) {
            engine.process(signal, offset)
            offset += hop
        }
        val perFrame = (bean.getThreadAllocatedBytes(tid) - before) / frames
        println("DSP allocation per frame: $perFrame bytes")
        // Результат: AudioFeatures + массив из 16 float — порядка 200 байт.
        assertTrue("allocated $perFrame bytes/frame", perFrame < 512)
    }
}
