package com.wledmusic.engine.core.audio

/**
 * Отличает паузу/тишину от работающего захвата и распознаёт вероятный запрет захвата:
 * система сообщает об активном воспроизведении музыки, а захваченный поток — цифровая тишина.
 * Это эвристика: запрет захвата Android явно не сообщает.
 */
class SilenceDetector(
    private val silenceAfterMs: Long = 1_500,
    private val blockedAfterMs: Long = 5_000,
) {
    data class Result(val silent: Boolean, val captureBlockedSuspected: Boolean)

    private var lastSignalMs: Long? = null
    private var digitalSilenceWhilePlayingSinceMs: Long? = null
    private var startedMs: Long? = null

    /**
     * @param signalPresent сигнал выше порога шума
     * @param digitalSilence поток состоит из нулей (не просто тихий)
     * @param musicActive система сообщает об активном воспроизведении музыки
     */
    fun update(nowMs: Long, signalPresent: Boolean, digitalSilence: Boolean, musicActive: Boolean): Result {
        if (startedMs == null) startedMs = nowMs
        if (signalPresent) lastSignalMs = nowMs

        if (digitalSilence && musicActive) {
            if (digitalSilenceWhilePlayingSinceMs == null) digitalSilenceWhilePlayingSinceMs = nowMs
        } else {
            digitalSilenceWhilePlayingSinceMs = null
        }

        val silentSince = lastSignalMs ?: startedMs!!
        val silent = !signalPresent && nowMs - silentSince >= silenceAfterMs
        val blocked = digitalSilenceWhilePlayingSinceMs?.let { nowMs - it >= blockedAfterMs } ?: false
        return Result(silent = silent, captureBlockedSuspected = blocked)
    }

    fun reset() {
        lastSignalMs = null
        digitalSilenceWhilePlayingSinceMs = null
        startedMs = null
    }
}
