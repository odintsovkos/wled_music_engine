package com.wledmusic.engine.core.dsp

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * DSP-движок: принимает моно-PCM блоками по [DspConfig.hopSize] отсчётов (float, −1..1)
 * и выдаёт [AudioFeatures] на каждый блок. Анализ ведётся скользящим окном [DspConfig.fftSize].
 *
 * Не потокобезопасен: [process] вызывается из одного DSP-потока. Исключение — [reconfigure]:
 * его можно вызывать из любого потока, новая конфигурация применяется в начале следующего кадра.
 */
class DspEngine(config: DspConfig = DspConfig()) {
    /** Текущая конфигурация; меняется только в DSP-потоке при применении [reconfigure]. */
    @Volatile var config: DspConfig = config
        private set
    private val pending = AtomicReference<DspConfig?>(null)

    private val fft = Fft(config.fftSize)
    private val window = FloatArray(config.fftSize)
    private val magnitudes = FloatArray(fft.binCount)
    private val binHz = config.sampleRate.toFloat() / config.fftSize

    private val channelBands = SpectrumBands(
        config.sampleRate, config.fftSize,
        SpectrumBands.logRanges(config.bandCount, config.bandMinHz, config.bandMaxHz),
    )
    private var bmhBands = SpectrumBands(
        config.sampleRate, config.fftSize,
        listOf(config.bassRange, config.midRange, config.highRange),
    )
    private val channelValues = FloatArray(config.bandCount)
    private val bmhValues = FloatArray(3)

    private val frameMs = config.frameMs
    private val agcFloor = dbToLinear(config.agcFloorDb)
    private val fixedLevelRef = dbToLinear(FIXED_REFERENCE_DB)
    private val fixedMagnitudeRef = fixedLevelRef * kotlin.math.sqrt(2f)
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

    private var onset = OnsetDetector(fft.binCount, config)
    private val peakSearchStart = ceil(config.bandMinHz / binHz).toInt().coerceAtLeast(1)

    /** Обрабатывает [config.hopSize] отсчётов из [samples], начиная с [offset]. */
    fun process(samples: FloatArray, offset: Int = 0): AudioFeatures {
        pending.getAndSet(null)?.let(::apply)
        val config = config
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
        // Детектор обновляется и при выключенной детекции, чтобы история flux была актуальной при включении.
        val peak = onset.update(magnitudes, gateOpen) && config.beatDetection

        val rawLevel: Float
        if (gateOpen && !config.autoGain) {
            rawLevel = (rms / fixedLevelRef).coerceIn(0f, 1f)
            for (i in 0 until 3) bmhValues[i] = (bmhValues[i] / fixedMagnitudeRef).coerceIn(0f, 1f)
            for (i in channelValues.indices) channelValues[i] = (channelValues[i] / fixedMagnitudeRef).coerceIn(0f, 1f)
        } else if (gateOpen) {
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
            majorPeakLevel = when {
                !gateOpen -> 0f
                config.autoGain -> channelGain.scale(peakMag)
                else -> (peakMag / fixedMagnitudeRef).coerceIn(0f, 1f)
            },
            zeroCrossings = zeroCrossings,
            gateOpen = gateOpen,
        )
    }

    /**
     * Запрашивает смену конфигурации без остановки захвата. Меняться могут только параметры,
     * совместимые по [DspConfig.isLiveCompatibleWith]; состояние AGC и сглаживания сохраняется.
     */
    fun reconfigure(newConfig: DspConfig) {
        require(newConfig.isLiveCompatibleWith(config)) { "sample rate, FFT, hop and channel grid cannot change live" }
        pending.set(newConfig)
    }

    private fun apply(new: DspConfig) {
        val old = config
        config = new
        if (new.attackMs != old.attackMs || new.releaseMs != old.releaseMs) {
            levelSmooth.setTimes(new.attackMs, new.releaseMs, frameMs)
            bmhSmooth.forEach { it.setTimes(new.attackMs, new.releaseMs, frameMs) }
            channelSmooth.forEach { it.setTimes(new.attackMs, new.releaseMs, frameMs) }
        }
        if (new.bassRange != old.bassRange || new.midRange != old.midRange || new.highRange != old.highRange) {
            bmhBands = SpectrumBands(new.sampleRate, new.fftSize, listOf(new.bassRange, new.midRange, new.highRange))
        }
        gate.thresholdDb = new.noiseGateDb
        gate.hysteresisDb = new.noiseGateHysteresisDb
        if (new.onsetWindowMs != old.onsetWindowMs || new.onsetThresholdK != old.onsetThresholdK ||
            new.onsetMinFlux != old.onsetMinFlux || new.onsetRefractoryMs != old.onsetRefractoryMs
        ) onset = OnsetDetector(fft.binCount, new)
        // AGC-параметры (agcRiseMs и т.п.) в Pro Audio не выносятся и live не меняются.
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

    private companion object {
        /** Опорный уровень при выключенном AGC: RMS −20 dBFS даёт уровень 1.0. */
        const val FIXED_REFERENCE_DB = -20f
    }
}
