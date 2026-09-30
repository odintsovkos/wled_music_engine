package com.wledmusic.engine.core.dsp

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Генераторы синтетических сигналов для тестов. */
object Signals {
    fun sine(hz: Float, seconds: Float, amplitude: Float = 0.5f, sampleRate: Int = 48_000): FloatArray {
        val n = (seconds * sampleRate).toInt()
        return FloatArray(n) { (amplitude * sin(2.0 * PI * hz * it / sampleRate)).toFloat() }
    }

    fun silence(seconds: Float, sampleRate: Int = 48_000) = FloatArray((seconds * sampleRate).toInt())

    fun whiteNoise(seconds: Float, rmsDb: Float, sampleRate: Int = 48_000, seed: Int = 1): FloatArray {
        val random = Random(seed)
        // Равномерный шум [-a, a] имеет RMS a/√3.
        val a = dbToLinear(rmsDb) * kotlin.math.sqrt(3f)
        return FloatArray((seconds * sampleRate).toInt()) { (random.nextFloat() * 2f - 1f) * a }
    }

    /** Щелчки: короткие затухающие всплески 2 кГц длительностью ~10 мс с частотой [rateHz]. */
    fun clicks(rateHz: Float, seconds: Float, sampleRate: Int = 48_000): FloatArray {
        val out = FloatArray((seconds * sampleRate).toInt())
        val period = (sampleRate / rateHz).toInt()
        val clickLen = sampleRate / 100
        var start = 0
        while (start < out.size) {
            for (i in 0 until clickLen) {
                if (start + i >= out.size) break
                val t = i.toDouble() / sampleRate
                out[start + i] = (0.8 * exp(-t * 300) * sin(2.0 * PI * 2000 * t)).toFloat()
            }
            start += period
        }
        return out
    }

    /** «Музыкальный» тестовый фрагмент: бас-удары, аккорд и хэт с огибающими. */
    fun musicLike(seconds: Float, gain: Float = 1f, sampleRate: Int = 48_000): FloatArray {
        val random = Random(42)
        val n = (seconds * sampleRate).toInt()
        val beat = sampleRate / 2
        return FloatArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val tb = (i % beat).toDouble() / sampleRate
            val kick = exp(-tb * 12) * sin(2.0 * PI * 55 * t)
            val chord = 0.25 * (sin(2.0 * PI * 220 * t) + sin(2.0 * PI * 277 * t) + sin(2.0 * PI * 330 * t)) *
                (0.6 + 0.4 * sin(2.0 * PI * 0.25 * t))
            val hat = exp(-((i + beat / 2) % beat).toDouble() / sampleRate * 60) * (random.nextDouble() * 2 - 1) * 0.3
            (gain * 0.4 * (kick + chord + hat)).toFloat()
        }
    }

    /** Прогоняет сигнал через движок блоками hop, возвращает признаки каждого кадра. */
    fun run(engine: DspEngine, signal: FloatArray): List<AudioFeatures> {
        val hop = engine.config.hopSize
        val out = ArrayList<AudioFeatures>(signal.size / hop)
        var offset = 0
        while (offset + hop <= signal.size) {
            out += engine.process(signal, offset)
            offset += hop
        }
        return out
    }
}
