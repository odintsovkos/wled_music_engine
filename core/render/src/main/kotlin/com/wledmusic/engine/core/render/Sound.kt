package com.wledmusic.engine.core.render

import com.wledmusic.engine.core.dsp.AudioFeatures
import kotlin.math.exp
import kotlin.math.pow

/**
 * Аудиовход эффектов на текущем кадре рендера: признаки DSP после чувствительности и огибающей
 * тишины. Изменяемый объект, переиспользуется между кадрами (без аллокаций).
 */
class Sound(bandCount: Int = 16) {
    var level = 0f; private set
    var rawLevel = 0f; private set
    var bass = 0f; private set
    var mid = 0f; private set
    var high = 0f; private set
    val bands = FloatArray(bandCount)
    /** Новый акцент на этом кадре (каждый кадр DSP учитывается один раз). */
    var beat = false; private set
    var majorPeakHz = 0f; private set
    /** 0..1: 1 — звук есть, 0 — тишина; спадает до 0 за ≤ 1 с после закрытия gate. */
    var activity = 0f; private set

    private var lastFrame: AudioFeatures? = null

    fun update(features: AudioFeatures?, sensitivity: Int, dtMs: Float) {
        val fresh = features != null && features !== lastFrame
        lastFrame = features
        val open = features?.gateOpen == true
        val tau = if (open) ACTIVITY_RISE_MS else ACTIVITY_FALL_MS
        val k = exp(-dtMs / tau)
        activity = (if (open) 1f else 0f) + (activity - if (open) 1f else 0f) * k
        if (activity < 0.01f) activity = 0f

        val gain = gainFor(sensitivity) * activity
        if (features == null) {
            level = 0f; rawLevel = 0f; bass = 0f; mid = 0f; high = 0f; bands.fill(0f)
            beat = false; majorPeakHz = 0f
            return
        }
        level = (features.level * gain).coerceIn(0f, 1f)
        rawLevel = (features.rawLevel * gain).coerceIn(0f, 1f)
        bass = (features.bass * gain).coerceIn(0f, 1f)
        mid = (features.mid * gain).coerceIn(0f, 1f)
        high = (features.high * gain).coerceIn(0f, 1f)
        val n = minOf(bands.size, features.bands.size)
        for (i in 0 until n) bands[i] = (features.bands[i] * gain).coerceIn(0f, 1f)
        beat = fresh && features.peak && open
        majorPeakHz = if (open) features.majorPeakHz else 0f
    }

    companion object {
        const val ACTIVITY_RISE_MS = 50f
        /** τ = 200 мс: к 1 с остаётся < 1 % — «покой» за ≤ 1 с. */
        const val ACTIVITY_FALL_MS = 200f

        /** Чувствительность 0..100 → усиление 0.25..4 (50 → 1). */
        fun gainFor(sensitivity: Int): Float = 2f.pow((sensitivity.coerceIn(0, 100) - 50) / 25f)
    }
}
