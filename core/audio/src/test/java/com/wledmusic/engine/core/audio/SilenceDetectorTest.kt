package com.wledmusic.engine.core.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceDetectorTest {
    private fun SilenceDetector.feed(fromMs: Long, toMs: Long, signal: Boolean, digital: Boolean, music: Boolean): SilenceDetector.Result {
        var last: SilenceDetector.Result? = null
        var t = fromMs
        while (t <= toMs) {
            last = update(t, signal, digital, music)
            t += 20
        }
        return last!!
    }

    @Test
    fun pauseBecomesSilenceWithinTwoSeconds() {
        val d = SilenceDetector()
        assertFalse(d.feed(0, 3_000, signal = true, digital = false, music = true).silent)
        assertFalse(d.feed(3_020, 4_000, signal = false, digital = true, music = false).silent)
        assertTrue(d.feed(4_020, 5_000, signal = false, digital = true, music = false).silent)
    }

    @Test
    fun resumingMusicClearsSilence() {
        val d = SilenceDetector()
        assertTrue(d.feed(0, 3_000, signal = false, digital = true, music = false).silent)
        assertFalse(d.update(3_020, signalPresent = true, digitalSilence = false, musicActive = true).silent)
    }

    @Test
    fun digitalSilenceWhileMusicPlaysSuggestsBlockedCapture() {
        val d = SilenceDetector()
        assertFalse(d.feed(0, 4_900, signal = false, digital = true, music = true).captureBlockedSuspected)
        assertTrue(d.feed(4_920, 5_200, signal = false, digital = true, music = true).captureBlockedSuspected)
    }

    @Test
    fun quietButNonZeroAudioIsNotBlocked() {
        val d = SilenceDetector()
        assertFalse(d.feed(0, 10_000, signal = false, digital = false, music = true).captureBlockedSuspected)
    }

    @Test
    fun noMusicMeansNoBlockedHint() {
        val d = SilenceDetector()
        assertFalse(d.feed(0, 10_000, signal = false, digital = true, music = false).captureBlockedSuspected)
    }

    @Test
    fun blockedHintClearsWhenAudioArrives() {
        val d = SilenceDetector()
        assertTrue(d.feed(0, 6_000, signal = false, digital = true, music = true).captureBlockedSuspected)
        assertFalse(d.update(6_020, signalPresent = true, digitalSilence = false, musicActive = true).captureBlockedSuspected)
    }
}
