package com.wledmusic.engine.session

import com.wledmusic.engine.core.dsp.AudioFeatures
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Единственный источник правды о сессии для UI и уведомления. Живёт в процессе приложения:
 * после его завершения системой состояние начинается заново с [CaptureStatus.INACTIVE].
 */
class SessionStateRepository {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _features = MutableStateFlow<AudioFeatures?>(null)
    /** Последние аудиопризнаки; null — захват не идёт. Обновляется с частотой DSP-кадров. */
    val features: StateFlow<AudioFeatures?> = _features.asStateFlow()

    fun onSessionStarted(target: String) = _state.update {
        it.copy(
            capture = CaptureStatus.ACTIVE, captureBlockedSuspected = false,
            transport = TransportStatus.Idle, packetsSent = 0, target = target, stopReason = null,
        )
    }

    fun onPermissionDenied() = _state.update {
        if (it.isRunning) it else it.copy(capture = CaptureStatus.NO_PERMISSION)
    }

    /** Смена «звук есть / тишина» учитывается только во время работающей сессии. */
    fun onSilenceChanged(silent: Boolean, captureBlockedSuspected: Boolean) = _state.update {
        if (!it.isRunning) it else it.copy(
            capture = if (silent) CaptureStatus.SILENCE else CaptureStatus.ACTIVE,
            captureBlockedSuspected = captureBlockedSuspected,
        )
    }

    fun onFeatures(features: AudioFeatures) {
        if (_state.value.isRunning) _features.value = features
    }

    fun onTransport(status: TransportStatus, packetsSent: Long) = _state.update {
        if (!it.isRunning) it else it.copy(transport = status, packetsSent = packetsSent)
    }

    fun onDspTiming(avgMs: Float) = _state.update { it.copy(avgDspMs = avgMs) }

    fun onLamp(status: LampStatus) = _state.update { it.copy(lamp = status) }

    fun onStoppedByUser() {
        _features.value = null
        _state.update {
            it.copy(capture = CaptureStatus.INACTIVE, captureBlockedSuspected = false, transport = TransportStatus.Idle, stopReason = null)
        }
    }

    fun onStoppedBySystem(reason: String) {
        _features.value = null
        _state.update {
            // Если пользователь уже остановил сессию, системная остановка не должна перетирать статус.
            if (!it.isRunning) it else it.copy(
                capture = CaptureStatus.STOPPED_BY_SYSTEM, captureBlockedSuspected = false,
                transport = TransportStatus.Idle, stopReason = reason,
            )
        }
    }
}
