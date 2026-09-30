package com.wledmusic.engine.session

import com.wledmusic.engine.core.dsp.AudioFeatures
import com.wledmusic.engine.core.network.LatencyMonitor
import com.wledmusic.engine.core.network.LatencySnapshot
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

    /** Задержки конвейера; пишут DSP-поток и отправители, читает служба раз в секунду. */
    val latency = LatencyMonitor()

    // Время готовности последних кадров признаков: отправитель находит «свой» кадр по ссылке.
    private val stampFrames = arrayOfNulls<AudioFeatures>(STAMPS)
    private val stampReady = LongArray(STAMPS)
    private var stampPos = 0

    fun onSessionStarted(target: String, mode: SyncMode = SyncMode.AUDIO_REACTIVE) {
        latency.reset()
        _state.update {
            it.copy(
                capture = CaptureStatus.ACTIVE, captureBlockedSuspected = false,
                transport = TransportStatus.Idle, packetsSent = 0, target = target, stopReason = null,
                mode = mode, avgRenderMs = 0f, realtime = RealtimeReceive.UNKNOWN, restore = RestoreStatus.NONE,
                latency = null, latencyWarning = null,
            )
        }
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

    /** Кадр признаков готов в [readyNanos]; его захват начался в [captureNanos]. */
    fun onFeatures(features: AudioFeatures, captureNanos: Long, readyNanos: Long, estimated: Boolean) {
        latency.onAudio(readyNanos - captureNanos, estimated)
        synchronized(stampReady) {
            stampFrames[stampPos] = features
            stampReady[stampPos] = readyNanos
            stampPos = (stampPos + 1) % STAMPS
        }
        onFeatures(features)
    }

    /** Кадр [features] (или нарисованный по нему) ушёл в сеть в [sentNanos]. */
    fun onFrameSent(features: AudioFeatures?, sentNanos: Long) {
        if (features == null) return
        val ready = synchronized(stampReady) {
            val i = stampFrames.indexOfFirst { it === features }
            if (i < 0) return else stampReady[i]
        }
        latency.onSend(sentNanos - ready)
    }

    fun onLatency(snapshot: LatencySnapshot, warning: LatencySnapshot.Stage?) =
        _state.update { if (!it.isRunning) it else it.copy(latency = snapshot, latencyWarning = warning) }

    fun onRenderTiming(avgMs: Float) = _state.update { it.copy(avgRenderMs = avgMs) }

    fun onRealtime(status: RealtimeReceive) = _state.update { if (!it.isRunning) it else it.copy(realtime = status) }

    fun onRestore(status: RestoreStatus) = _state.update { it.copy(restore = status) }

    fun onModeSelected(mode: SyncMode) = _state.update { if (it.isRunning) it else it.copy(mode = mode) }

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

    private companion object {
        const val STAMPS = 8
    }
}
