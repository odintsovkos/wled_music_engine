package com.wledmusic.engine.session

import com.wledmusic.engine.core.network.WledInfo
import com.wledmusic.engine.core.network.WledInfoResult

enum class CaptureStatus { INACTIVE, ACTIVE, SILENCE, NO_PERMISSION, STOPPED_BY_SYSTEM }

sealed interface LampStatus {
    data object Unknown : LampStatus
    data object Checking : LampStatus
    data class Available(val info: WledInfo) : LampStatus
    data class Unavailable(val reason: WledInfoResult.Reason, val message: String?) : LampStatus
}

sealed interface TransportStatus {
    data object Idle : TransportStatus
    data object Sending : TransportStatus
    data object NoNetwork : TransportStatus
    data class Error(val message: String) : TransportStatus
}

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
) {
    /** Сессия считается работающей только при реально идущем захвате. */
    val isRunning: Boolean get() = capture == CaptureStatus.ACTIVE || capture == CaptureStatus.SILENCE
}
