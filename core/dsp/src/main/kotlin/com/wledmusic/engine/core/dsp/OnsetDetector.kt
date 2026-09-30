package com.wledmusic.engine.core.dsp

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Обнаружение акцентов по spectral flux: средний по бинам положительный прирост
 * лог-сжатой амплитуды. Порог адаптивный — среднее + k·σ по истории flux,
 * но не ниже абсолютного минимума; после срабатывания действует refractory-интервал.
 */
class OnsetDetector(binCount: Int, config: DspConfig) {
    private val previous = FloatArray(binCount)
    private val history = FloatArray(max(2, (config.onsetWindowMs / config.frameMs).roundToInt()))
    private var historyFilled = 0
    private var historyPos = 0
    private val refractoryFrames = (config.onsetRefractoryMs / config.frameMs).let { kotlin.math.ceil(it).toInt() }
    private var framesSinceOnset = Int.MAX_VALUE / 2
    private val k = config.onsetThresholdK
    private val minFlux = config.onsetMinFlux
    private var primed = false

    /** Последнее значение flux (для диагностики). */
    var lastFlux: Float = 0f
        private set

    fun update(magnitudes: FloatArray, gateOpen: Boolean): Boolean {
        var flux = 0f
        for (i in previous.indices) {
            val compressed = ln(1f + COMPRESSION * magnitudes[i])
            val diff = compressed - previous[i]
            if (diff > 0f) flux += diff
            previous[i] = compressed
        }
        flux /= previous.size
        if (!primed) {
            // Первый кадр сравнивать не с чем.
            primed = true
            flux = 0f
        }
        lastFlux = flux

        val threshold = max(minFlux, mean() + k * stdDev())
        framesSinceOnset++
        val onset = gateOpen && flux > threshold && framesSinceOnset >= refractoryFrames
        if (onset) framesSinceOnset = 0

        history[historyPos] = flux
        historyPos = (historyPos + 1) % history.size
        if (historyFilled < history.size) historyFilled++
        return onset
    }

    fun reset() {
        previous.fill(0f)
        history.fill(0f)
        historyFilled = 0
        historyPos = 0
        framesSinceOnset = Int.MAX_VALUE / 2
        primed = false
    }

    private fun mean(): Float {
        if (historyFilled == 0) return 0f
        var sum = 0f
        for (i in 0 until historyFilled) sum += history[i]
        return sum / historyFilled
    }

    private fun stdDev(): Float {
        if (historyFilled < 2) return 0f
        val m = mean()
        var sum = 0f
        for (i in 0 until historyFilled) {
            val d = history[i] - m
            sum += d * d
        }
        return sqrt(sum / historyFilled)
    }

    private companion object {
        const val COMPRESSION = 1000f
    }
}
