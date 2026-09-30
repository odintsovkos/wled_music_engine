package com.wledmusic.engine.core.dsp

import kotlin.math.exp

/** Noise gate по уровню в dBFS с гистерезисом закрытия. */
class NoiseGate(var thresholdDb: Float, var hysteresisDb: Float) {
    var isOpen: Boolean = false
        private set

    fun update(levelDb: Float): Boolean {
        isOpen = if (isOpen) levelDb >= thresholdDb - hysteresisDb else levelDb >= thresholdDb
        return isOpen
    }

    fun reset() {
        isOpen = false
    }
}

/**
 * Сглаживание однополюсным фильтром с раздельными временами нарастания и спада.
 * [attackMs] и [releaseMs] — постоянные времени (τ), [frameMs] — шаг обновления.
 */
class AttackRelease(attackMs: Float, releaseMs: Float, frameMs: Float) {
    private var attackCoef = exp(-frameMs / attackMs)
    private var releaseCoef = exp(-frameMs / releaseMs)

    /** Меняет постоянные времени, сохраняя текущее значение (без скачка). */
    fun setTimes(attackMs: Float, releaseMs: Float, frameMs: Float) {
        attackCoef = exp(-frameMs / attackMs)
        releaseCoef = exp(-frameMs / releaseMs)
    }

    var value: Float = 0f
        private set

    fun update(target: Float): Float {
        val coef = if (target > value) attackCoef else releaseCoef
        value = target + (value - target) * coef
        return value
    }

    fun reset() {
        value = 0f
    }
}

/**
 * Нормализация к среднему уровню, как AGC в WLED: средний уровень сигнала приводится к [target],
 * а всплески (удары) поднимаются выше, вплоть до 1. В отличие от нормализации к пику, плотная
 * громкая музыка не «прилипает» к максимуму, и у эффектов остаётся динамика.
 * Среднее растёт быстрее ([riseMs]), чем спадает ([fallMs]), чтобы после тишины не было долгого клиппинга.
 */
class AverageGain(
    riseMs: Float,
    fallMs: Float,
    frameMs: Float,
    private val floor: Float,
    private val target: Float,
) {
    private val riseCoef = exp(-frameMs / riseMs)
    private val fallCoef = exp(-frameMs / fallMs)
    private var primed = false

    var average: Float = floor
        private set

    fun normalize(value: Float): Float {
        if (!primed) {
            average = maxOf(value, floor)
            primed = true
        } else {
            val coef = if (value > average) riseCoef else fallCoef
            average = maxOf(value + (average - value) * coef, floor)
        }
        return (value * target / average).coerceIn(0f, 1f)
    }

    fun reset() {
        average = floor
        primed = false
    }
}

/**
 * Адаптивная нормализация: опорный уровень мгновенно поднимается до пиков сигнала
 * и медленно спадает, но не ниже [floor]. Выход = значение / опорный уровень, 0..1.
 */
class AutoGain(decayMs: Float, frameMs: Float, private val floor: Float) {
    private val decayCoef = exp(-frameMs / decayMs)

    var reference: Float = floor
        private set

    fun normalize(value: Float): Float {
        update(value)
        return scale(value)
    }

    /** Обновляет опорный уровень по пиковому значению кадра. */
    fun update(peak: Float) {
        reference = maxOf(peak, reference * decayCoef, floor)
    }

    /** Нормализует значение текущим опорным уровнем без его обновления. */
    fun scale(value: Float): Float = (value / reference).coerceIn(0f, 1f)

    fun reset() {
        reference = floor
    }
}
