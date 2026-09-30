package com.wledmusic.engine.core.dsp

/**
 * Аудиопризнаки одного DSP-кадра. Нормализованные значения лежат в диапазоне 0..1.
 */
class AudioFeatures(
    /** RMS кадра, линейная амплитуда (без нормализации). */
    val rms: Float,
    /** Нормализованный уровень без сглаживания. */
    val rawLevel: Float,
    /** Нормализованный сглаженный уровень. */
    val level: Float,
    val bass: Float,
    val mid: Float,
    val high: Float,
    /** Нормализованные сглаженные частотные каналы (обычно 16). */
    val bands: FloatArray,
    /** В кадре обнаружен музыкальный акцент. */
    val peak: Boolean,
    /** Доминирующая частота, Гц. */
    val majorPeakHz: Float,
    /** Амплитуда доминирующей частоты, линейная. */
    val majorPeakMagnitude: Float,
    /** Амплитуда доминирующей частоты, нормализованная тем же опорным уровнем, что и [bands], 0..1. */
    val majorPeakLevel: Float,
    val zeroCrossings: Int,
    /** Сигнал выше порога шума. */
    val gateOpen: Boolean,
) {
    companion object {
        fun silent(bandCount: Int = 16) = AudioFeatures(
            rms = 0f, rawLevel = 0f, level = 0f, bass = 0f, mid = 0f, high = 0f,
            bands = FloatArray(bandCount), peak = false, majorPeakHz = 0f,
            majorPeakMagnitude = 0f, majorPeakLevel = 0f, zeroCrossings = 0, gateOpen = false,
        )
    }
}
