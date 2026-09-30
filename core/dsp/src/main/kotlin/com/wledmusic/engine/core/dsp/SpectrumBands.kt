package com.wledmusic.engine.core.dsp

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Свёртка амплитудного спектра в частотные полосы.
 * Значение полосы — корень из суммы квадратов амплитуд её бинов (энергия полосы).
 * Для логарифмической сетки розовый шум даёт примерно одинаковые значения во всех полосах.
 */
class SpectrumBands(
    sampleRate: Int,
    fftSize: Int,
    ranges: List<ClosedFloatingPointRange<Float>>,
) {
    private val binHz = sampleRate.toFloat() / fftSize
    private val maxBin = fftSize / 2
    private val startBins = IntArray(ranges.size)
    private val endBins = IntArray(ranges.size)

    init {
        ranges.forEachIndexed { i, r ->
            val start = min(maxBin, max(1, ceil(r.start / binHz).toInt()))
            val end = min(maxBin, ceil(r.endInclusive / binHz).toInt() - 1)
            startBins[i] = start
            // Узкая полоса без собственных бинов берёт ближайший.
            endBins[i] = max(start, end)
        }
    }

    val count: Int get() = startBins.size

    fun compute(magnitudes: FloatArray, out: FloatArray) {
        for (i in 0 until count) {
            var sum = 0f
            for (k in startBins[i]..endBins[i]) sum += magnitudes[k] * magnitudes[k]
            out[i] = sqrt(sum)
        }
    }

    companion object {
        /** Логарифмическая сетка из [count] полос от [minHz] до [maxHz]. */
        fun logRanges(count: Int, minHz: Float, maxHz: Float): List<ClosedFloatingPointRange<Float>> {
            val ratio = (maxHz / minHz).toDouble()
            val edges = FloatArray(count + 1) { (minHz * ratio.pow(it.toDouble() / count)).toFloat() }
            return List(count) { edges[it]..edges[it + 1] }
        }
    }
}

internal fun linearToDb(value: Float): Float =
    if (value <= 0f) Float.NEGATIVE_INFINITY else (20.0 * ln(value.toDouble()) / ln(10.0)).toFloat()

internal fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()
