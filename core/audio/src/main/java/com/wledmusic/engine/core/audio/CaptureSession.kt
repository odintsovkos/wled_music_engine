package com.wledmusic.engine.core.audio

import android.Manifest
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission

/**
 * Сессия захвата: владеет [MediaProjection] и [PlaybackCapture].
 * Любая остановка по инициативе системы (отзыв проекции, ошибка AudioRecord) приходит в
 * [onStoppedBySystem] ровно один раз. Сессия не перезапускается: для новой нужен новый токен
 * MediaProjection, т.е. новое согласие пользователя.
 */
class CaptureSession(
    private val projection: MediaProjection,
    private val onStoppedBySystem: (reason: String) -> Unit,
    sampleRate: Int = 48_000,
    blockFrames: Int = 1024,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var finished = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            finishBySystem("MediaProjection stopped")
        }
    }

    val capture = PlaybackCapture(projection, sampleRate, blockFrames) { error ->
        mainHandler.post { finishBySystem(error) }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        // Android 14+: callback обязан быть зарегистрирован до начала захвата.
        projection.registerCallback(projectionCallback, mainHandler)
        try {
            capture.start()
        } catch (e: Exception) {
            release()
            throw e
        }
    }

    /** Штатная остановка по действию пользователя. */
    fun stop() {
        if (finished) return
        finished = true
        release()
    }

    private fun finishBySystem(reason: String) {
        if (finished) return
        finished = true
        release()
        onStoppedBySystem(reason)
    }

    private fun release() {
        capture.stop()
        projection.unregisterCallback(projectionCallback)
        projection.stop()
    }
}
