package com.wledmusic.engine.core.dsp

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * DSP-движок: принимает моно-PCM блоками по [DspConfig.hopSize] отсчётов (float, −1..1)
 * и выдаёт [AudioFeatures] на каждый блок. Анализ ведётся скользящим окном [DspConfig.fftSize].
 *
 * Не потокобезопасен: вызывается из одного DSP-потока.
 */
class DspEngine(val config: DspConfig = DspConfig()) {
    private val fft = Fft(config.fftSize)
    private val window = FloatArray(config.fftSize)
    private val magnitudes = FloatArray(fft.binCount)
    private val binHz = config.sampleRate.toFloat() / config.fftSize

    private val channelBands = SpectrumBands(
        config.sampleRate, config.fftSize,
        SpectrumBands.logRanges(config.bandCount, config.bandMinHz, config.bandMaxHz),
    )
    private val bmhBands = SpectrumBands(
        config.sampleRate, config.fftSize,
        listOf(config.bassRange, config.midRange, config.highRange),
    )
    private val channelValues = FloatArray(config.bandCount)
    private val bmhValues = FloatArray(3)

    private val frameMs = config.frameMs
    private val agcFloor = dbToLinear(config.agcFloorDb)
    private val gate = NoiseGate(config.noiseGateDb, config.noiseGateHysteresisDb)

    // Громкость и полосы — к среднему (есть пульсация), спектральные каналы — к общему пику (сохраняется форма спектра).
    private fun averageGain() = AverageGain(config.agcRiseMs, config.agcDecayMs, frameMs, agcFloor, config.agcTarget)
    private val levelGain = averageGain()
    private val bmhGains = Array(3) { averageGain() }
    private val channelGain = AutoGain(config.agcDecayMs, frameMs, agcFloor)

    private fun smoother() = AttackRelease(config.attackMs, config.releaseMs, frameMs)
    private val levelSmooth = smoother()
    private val bmhSmooth = Array(3) { smoother() }
    private val channelSmooth = Array(config.bandCount) { smoother() }

    private val onset = OnsetDetector(fft.binCount, config)
    private val peakSearchStart = ceil(config.bandMinHz / binHz).toInt().coerceAtLeast(1)

    /** Обрабатывает [config.hopSize] отсчётов из [samples], начиная с [offset]. */
    fun process(samples: FloatArray, offset: Int = 0): AudioFeatures {
        val hop = config.hopSize
        require(offset >= 0 && offset + hop <= samples.size) { "not enough samples for a hop" }

        // Сдвиг окна и дописывание нового блока.
        System.arraycopy(window, hop, window, 0, window.size - hop)
        System.arraycopy(samples, offset, window, window.size - hop, hop)

        var sumSquares = 0f
        var zeroCrossings = 0
        var prev = window[window.size - hop - 1]
        for (i in offset until offset + hop) {
            val s = samples[i]
            sumSquares += s * s
            if ((s >= 0f) != (prev >= 0f)) zeroCrossings++
            prev = s
        }
        val rms = sqrt(sumSquares / hop)
        val gateOpen = gate.update(linearToDb(rms))

        fft.magnitudes(window, magnitudes)
        channelBands.compute(magnitudes, channelValues)
        bmhBands.compute(magnitudes, bmhValues)
        val peak = onset.update(magnitudes, gateOpen)

        val rawLevel: Float
        if (gateOpen) {
            rawLevel = levelGain.normalize(rms)
            for (i in 0 until 3) bmhValues[i] = bmhGains[i].normalize(bmhValues[i])
            var maxChannel = 0f
            for (v in channelValues) if (v > maxChannel) maxChannel = v
            channelGain.update(maxChannel)
            for (i in channelValues.indices) channelValues[i] = channelGain.scale(channelValues[i])
        } else {
            rawLevel = 0f
            bmhValues.fill(0f)
            channelValues.fill(0f)
        }

        val bands = FloatArray(config.bandCount) { channelSmooth[it].update(channelValues[it]) }
        if (gateOpen) findMajorPeak() else { peakHz = 0f; peakMag = 0f }

        return AudioFeatures(
            rms = rms,
            rawLevel = rawLevel,
            level = levelSmooth.update(rawLevel),
            bass = bmhSmooth[0].update(bmhValues[0]),
            mid = bmhSmooth[1].update(bmhValues[1]),
            high = bmhSmooth[2].update(bmhValues[2]),
            bands = bands,
            peak = peak,
            majorPeakHz = peakHz,
            majorPeakMagnitude = peakMag,
            majorPeakLevel = if (gateOpen) channelGain.scale(peakMag) else 0f,
            zeroCrossings = zeroCrossings,
            gateOpen = gateOpen,
        )
    }

    fun reset() {
        window.fill(0f)
        gate.reset()
        levelGain.reset(); channelGain.reset(); bmhGains.forEach { it.reset() }
        levelSmooth.reset(); bmhSmooth.forEach { it.reset() }; channelSmooth.forEach { it.reset() }
        onset.reset()
    }

    private var peakHz = 0f
    private var peakMag = 0f

    /** Доминирующая частота с гауссовой интерполяцией по соседним бинам → [peakHz], [peakMag]. */
    private fun findMajorPeak() {
        val last = magnitudes.size - 2
        var best = peakSearchStart
        for (k in peakSearchStart..last) if (magnitudes[k] > magnitudes[best]) best = k
        val a = magnitudes[best - 1]
        val b = magnitudes[best]
        val c = magnitudes[best + 1]
        peakHz = best * binHz
        peakMag = b
        if (a <= 0f || b <= 0f || c <= 0f) return
        val la = ln(a); val lb = ln(b); val lc = ln(c)
        val denom = la - 2 * lb + lc
        if (denom >= 0f) return
        val delta = (0.5f * (la - lc) / denom).coerceIn(-0.5f, 0.5f)
        peakHz = (best + delta) * binHz
        peakMag = exp(lb - 0.25f * (la - lc) * delta)
    }
}
