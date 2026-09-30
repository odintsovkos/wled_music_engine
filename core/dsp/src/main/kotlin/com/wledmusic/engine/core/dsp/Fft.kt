package com.wledmusic.engine.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Итеративный radix-2 FFT для вещественного сигнала с окном Ханна.
 * Все буферы выделяются в конструкторе; [magnitudes] не аллоцирует.
 */
class Fft(val size: Int) {
    init {
        require(size >= 2 && size and (size - 1) == 0) { "size must be a power of two" }
    }

    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private val cosTable = FloatArray(size / 2) { cos(2.0 * PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { -sin(2.0 * PI * it / size).toFloat() }
    private val bitReverse = IntArray(size).also { table ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) table[i] = Integer.reverse(i) ushr (32 - bits)
    }

    val window = FloatArray(size) { (0.5 - 0.5 * cos(2.0 * PI * it / size)).toFloat() }

    /** Коэффициент, при котором синусоида амплитуды A даёт в пике спектра ≈ A. */
    private val amplitudeScale = 2f / window.sum()

    /** Число выходных бинов: size / 2 + 1. */
    val binCount: Int get() = size / 2 + 1

    /**
     * Вычисляет амплитудный спектр [input] (длина [size]) с окном Ханна в [out] (длина ≥ [binCount]).
     */
    fun magnitudes(input: FloatArray, out: FloatArray) {
        require(input.size >= size && out.size >= binCount)
        for (i in 0 until size) {
            val j = bitReverse[i]
            re[j] = input[i] * window[i]
            im[j] = 0f
        }
        transform()
        for (k in 0 until binCount) {
            out[k] = sqrt(re[k] * re[k] + im[k] * im[k]) * amplitudeScale
        }
        // DC и Найквист не удваиваются.
        out[0] *= 0.5f
        out[size / 2] *= 0.5f
    }

    /** Комплексный FFT на месте над [re]/[im], данные уже в bit-reversed порядке. */
    private fun transform() {
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var start = 0
            while (start < size) {
                var t = 0
                for (k in 0 until half) {
                    val wr = cosTable[t]
                    val wi = sinTable[t]
                    val a = start + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                    t += step
                }
                start += len
            }
            len = len shl 1
        }
    }
}
