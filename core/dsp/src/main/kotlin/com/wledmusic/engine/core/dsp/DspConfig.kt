package com.wledmusic.engine.core.dsp

/**
 * Параметры DSP-движка. Все времена — в миллисекундах, уровни — в dBFS.
 */
data class DspConfig(
    val sampleRate: Int = 48_000,
    val fftSize: Int = 2048,
    val hopSize: Int = 1024,

    val bassRange: ClosedFloatingPointRange<Float> = 20f..250f,
    val midRange: ClosedFloatingPointRange<Float> = 250f..4_000f,
    val highRange: ClosedFloatingPointRange<Float> = 4_000f..16_000f,

    val bandCount: Int = 16,
    val bandMinHz: Float = 43f,
    val bandMaxHz: Float = 9_000f,

    /** Ниже этого RMS сигнал считается шумом и выходы обнуляются. */
    val noiseGateDb: Float = -60f,
    /** Гистерезис закрытия gate. */
    val noiseGateHysteresisDb: Float = 3f,

    val attackMs: Float = 20f,
    val releaseMs: Float = 300f,

    /** Постоянная времени спада опорного уровня адаптивной нормализации. */
    val agcDecayMs: Float = 3_000f,
    /** Постоянная времени роста среднего уровня AGC громкости и полос. */
    val agcRiseMs: Float = 500f,
    /** К какому значению 0..1 приводится средний уровень громкости (WLED AGC держит ~110/255). */
    val agcTarget: Float = 0.45f,
    /** Минимальный опорный уровень AGC: тише этого сигнал не «дотягивается» до 1. */
    val agcFloorDb: Float = -50f,

    /** Длина истории spectral flux для адаптивного порога. */
    val onsetWindowMs: Float = 1_000f,
    /** Порог = среднее + k·σ. */
    val onsetThresholdK: Float = 2f,
    /** Абсолютный минимум flux (на бин) для срабатывания. */
    val onsetMinFlux: Float = 0.01f,
    val onsetRefractoryMs: Float = 100f,
) {
    init {
        require(fftSize > 0 && fftSize and (fftSize - 1) == 0) { "fftSize must be a power of two" }
        require(hopSize in 1..fftSize) { "hopSize must be in 1..fftSize" }
        require(bandCount > 0) { "bandCount must be positive" }
        require(agcTarget in 0.05f..1f) { "agcTarget must be in 0.05..1" }
        require(bandMinHz > 0f && bandMaxHz > bandMinHz) { "invalid band range" }
        require(attackMs > 0f && releaseMs > 0f && agcDecayMs > 0f && agcRiseMs > 0f) { "time constants must be positive" }
    }

    /** Длительность одного DSP-кадра в миллисекундах. */
    val frameMs: Float get() = hopSize * 1000f / sampleRate
}
