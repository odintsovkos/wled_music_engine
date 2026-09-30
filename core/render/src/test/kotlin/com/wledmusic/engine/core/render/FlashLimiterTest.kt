package com.wledmusic.engine.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashLimiterTest {
    @Test
    fun atMostThreePerSlidingSecond() {
        val limiter = FlashLimiter()
        val allowed = (0 until 60).map { it * 100L }.filter { limiter.tryFlash(it) }
        for (t in allowed) assertTrue(allowed.count { it in t until t + 1000 } <= 3)
        assertEquals(18, allowed.size) // 6 с × 3
    }

    @Test
    fun canFlashDoesNotConsume() {
        val limiter = FlashLimiter()
        repeat(10) { assertTrue(limiter.canFlash(0)) }
        repeat(3) { assertTrue(limiter.tryFlash(it.toLong())) }
        assertFalse(limiter.canFlash(500))
        assertTrue(limiter.canFlash(1_000))
    }
}
