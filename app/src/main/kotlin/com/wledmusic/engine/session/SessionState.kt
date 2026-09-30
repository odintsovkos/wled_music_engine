package com.wledmusic.engine.session

import com.wledmusic.engine.core.network.LatencySnapshot
import com.wledmusic.engine.core.network.WledInfo
import com.wledmusic.engine.core.network.WledInfoResult
import com.wledmusic.engine.core.network.WledState

enum class CaptureStatus { INACTIVE, ACTIVE, SILENCE, NO_PERMISSION, STOPPED_BY_SYSTEM }

/** Режим управления лампой. Подписи в UI: «Audio Reactive» и «RGB Engine». */
enum class SyncMode { AUDIO_REACTIVE, RGB_ENGINE }

sealed interface LampStatus {
    data object Unknown : LampStatus
    data object Checking : LampStatus
    data class Available(val info: WledInfo, val state: WledState? = null, val effectNames: List<String> = emptyList()) : LampStatus {
        /** Имя эффекта основного сегмента WLED; null — неизвестно. */
        val currentEffectName: String? get() = state?.main?.effect?.let { effectNames.getOrNull(it) }
    }
    data class Unavailable(val reason: WledInfoResult.Reason, val message: String?) : LampStatus
}

sealed interface TransportStatus {
    data object Idle : TransportStatus
    data object Sending : TransportStatus
    data object NoNetwork : TransportStatus
    data class Error(val message: String) : TransportStatus
}

/** Принимает ли лампа realtime-поток RGB Engine (по `info.live` / `info.lip`). */
enum class RealtimeReceive { UNKNOWN, RECEIVING, NOT_RECEIVING, OTHER_SOURCE }

/** Итог восстановления состояния лампы после сессии. */
enum class RestoreStatus { NONE, RESTORED, DEFERRED }

/** Снимок состояния сессии синхронизации (без высокочастотных аудиопризнаков). */
data class SessionState(
    val capture: CaptureStatus = CaptureStatus.INACTIVE,
    val captureBlockedSuspected: Boolean = false,
    val lamp: LampStatus = LampStatus.Unknown,
    val transport: TransportStatus = TransportStatus.Idle,
    val packetsSent: Long = 0,
    val target: String? = null,
    val stopReason: String? = null,
    val avgDspMs: Float = 0f,
    val mode: SyncMode = SyncMode.AUDIO_REACTIVE,
    val avgRenderMs: Float = 0f,
    val realtime: RealtimeReceive = RealtimeReceive.UNKNOWN,
    val restore: RestoreStatus = RestoreStatus.NONE,
    val latency: LatencySnapshot? = null,
    val latencyWarning: LatencySnapshot.Stage? = null,
) {
    /** Сессия считается работающей только при реально идущем захвате. */
    val isRunning: Boolean get() = capture == CaptureStatus.ACTIVE || capture == CaptureStatus.SILENCE
}
