package com.wledmusic.engine.session

import com.wledmusic.engine.core.dsp.AudioFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateRepositoryTest {
    private val repo = SessionStateRepository()

    @Test
    fun startsInactive() {
        assertEquals(CaptureStatus.INACTIVE, repo.state.value.capture)
        assertFalse(repo.state.value.isRunning)
    }

    @Test
    fun silenceTogglesOnlyWhileRunning() {
        repo.onSilenceChanged(silent = true, captureBlockedSuspected = false)
        assertEquals(CaptureStatus.INACTIVE, repo.state.value.capture)

        repo.onSessionStarted("192.168.1.10:11988")
        repo.onSilenceChanged(silent = true, captureBlockedSuspected = true)
        assertEquals(CaptureStatus.SILENCE, repo.state.value.capture)
        assertTrue(repo.state.value.captureBlockedSuspected)
        assertTrue(repo.state.value.isRunning)

        repo.onSilenceChanged(silent = false, captureBlockedSuspected = false)
        assertEquals(CaptureStatus.ACTIVE, repo.state.value.capture)
    }

    @Test
    fun systemStopIsVisibleAndClearsFeatures() {
        repo.onSessionStarted("wled.local:11988")
        repo.onFeatures(AudioFeatures.silent())
        assertNotNull(repo.features.value)
        repo.onTransport(TransportStatus.Sending, 10)

        repo.onStoppedBySystem("MediaProjection stopped")
        val s = repo.state.value
        assertEquals(CaptureStatus.STOPPED_BY_SYSTEM, s.capture)
        assertEquals(TransportStatus.Idle, s.transport)
        assertEquals("MediaProjection stopped", s.stopReason)
        assertNull(repo.features.value)
        assertFalse(s.isRunning)
    }

    @Test
    fun lateEventsAfterStopDoNotResurrectSession() {
        repo.onSessionStarted("x:1")
        repo.onStoppedBySystem("revoked")
        repo.onFeatures(AudioFeatures.silent())
        repo.onTransport(TransportStatus.Sending, 99)
        repo.onSilenceChanged(silent = false, captureBlockedSuspected = false)
        assertEquals(CaptureStatus.STOPPED_BY_SYSTEM, repo.state.value.capture)
        assertNull(repo.features.value)
    }

    @Test
    fun userStopWinsOverLaterSystemStop() {
        repo.onSessionStarted("x:1")
        repo.onStoppedByUser()
        repo.onStoppedBySystem("MediaProjection stopped")
        assertEquals(CaptureStatus.INACTIVE, repo.state.value.capture)
    }

    @Test
    fun permissionDeniedDoesNotBreakRunningSession() {
        repo.onPermissionDenied()
        assertEquals(CaptureStatus.NO_PERMISSION, repo.state.value.capture)
        repo.onSessionStarted("x:1")
        repo.onPermissionDenied()
        assertEquals(CaptureStatus.ACTIVE, repo.state.value.capture)
    }

    @Test
    fun newSessionResetsCounters() {
        repo.onSessionStarted("x:1")
        repo.onTransport(TransportStatus.Sending, 500)
        repo.onStoppedByUser()
        repo.onSessionStarted("x:1")
        assertEquals(0L, repo.state.value.packetsSent)
        assertNull(repo.state.value.stopReason)
    }
}
