package com.wledmusic.engine.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatencyMonitorTest {
    @Test
    fun mediansPerStage() {
        val m = LatencyMonitor()
        listOf(10, 30, 20, 500).forEach { m.onAudio(it * 1_000_000L, estimated = false) }
        listOf(3, 4, 5).forEach { m.onSend(it * 1_000_000L) }
        listOf(20f, 22f, 18f, 300f, 21f).forEach(m::onRtt)
        val s = m.snapshot()
        assertEquals(25f, s.audioToDspMs!!, 0.01f)
        assertEquals(4f, s.dspToNetworkMs!!, 0.01f)
        assertEquals(10.5f, s.networkMs!!, 0.01f) // медиана RTT 21 → ≈ 10,5 мс
        assertEquals(39.5f, s.totalMs!!, 0.01f)
    }

    @Test
    fun missingStageMeansNoTotal() {
        val m = LatencyMonitor()
        m.onAudio(10_000_000, estimated = true)
        val s = m.snapshot()
        assertNull(s.totalMs)
        assertEquals(true, s.audioEstimated)
    }

    @Test
    fun rttUsesLastFiveSamples() {
        val m = LatencyMonitor()
        repeat(5) { m.onRtt(200f) }
        repeat(5) { m.onRtt(20f) }
        assertEquals(10f, m.snapshot().networkMs!!, 0.01f)
    }

    @Test
    fun warningAfterFiveSecondsAboveThreshold() {
        val m = LatencyMonitor()
        val high = LatencySnapshot(20f, false, 10f, 80f)
        assertNull(m.warning(high, 0))
        assertNull(m.warning(high, 4_000))
        assertEquals(LatencySnapshot.Stage.NETWORK, m.warning(high, 5_000))
        assertNull(m.warning(LatencySnapshot(20f, false, 10f, 10f), 6_000))
        assertNull(m.warning(high, 7_000))
    }
}
