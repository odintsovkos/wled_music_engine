package com.wledmusic.engine.core.render

import com.wledmusic.engine.core.dsp.AudioFeatures

/** Синтетические кадры признаков для тестов рендера. */
object Frames {
    fun of(
        level: Float = 0.5f,
        bands: FloatArray = FloatArray(16) { level },
        peak: Boolean = false,
        gateOpen: Boolean = true,
        hz: Float = 440f,
        bass: Float = level,
    ) = AudioFeatures(
        rms = level, rawLevel = level, level = level, bass = bass, mid = level, high = level,
        bands = bands, peak = peak, majorPeakHz = hz, majorPeakMagnitude = level,
        majorPeakLevel = level, zeroCrossings = 0, gateOpen = gateOpen,
    )

    val silent get() = of(level = 0f, gateOpen = false)
}
