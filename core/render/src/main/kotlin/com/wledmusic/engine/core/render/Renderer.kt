package com.wledmusic.engine.core.render

import com.wledmusic.engine.core.dsp.AudioFeatures
import kotlin.math.pow

/** Что и как рисовать; неизменяемый снимок, который UI подменяет целиком. */
data class RenderSettings(
    val effect: EffectId = EffectId.SPECTRUM,
    val params: EffectParams = effect.defaultParams(),
    val direction: AnimationDirection = AnimationDirection.UP,
    val gamma: Boolean = false,
)

/**
 * Синхронный рендер одного кадра: звук → эффект → защита от вспышек → яркость → гамма.
 * Не потокобезопасен: вызывается из одного рендер-потока. Буферы выделяются в конструкторе;
 * при смене эффекта создаётся новый экземпляр эффекта (однократная аллокация).
 */
class Renderer(val layout: LedLayout) {
    val canvas = Canvas(layout)
    /** Итоговый кадр для отправки (порядок LED — как в DDP). */
    val output = ByteArray(layout.ledCount * 3)

    private val sound = Sound()
    private val flashLimiter = FlashLimiter()
    private val ctx = FrameContext(sound, flashLimiter)
    private var effectId: EffectId? = null
    private var effect: Effect? = null
    private var lastLuma = 0
    private val gammaLut = IntArray(256) { (255.0 * (it / 255.0).pow(GAMMA) + 0.5).toInt() }

    /** Рисует кадр для момента [nowMs] и возвращает [output]. */
    fun render(features: AudioFeatures?, nowMs: Long, dtMs: Float, settings: RenderSettings): ByteArray {
        if (settings.effect != effectId) {
            effectId = settings.effect
            canvas.clear()
            effect = settings.effect.create(canvas)
        }
        sound.update(features, settings.params.sensitivity, dtMs)
        ctx.nowMs = nowMs
        ctx.dtMs = dtMs
        ctx.params = settings.params
        ctx.palette = Palette.of(settings.params.palette)
        ctx.direction = settings.direction
        effect!!.render(ctx)
        limitFlashes(nowMs)
        postProcess(settings.params.brightness, settings.gamma)
        return output
    }

    /**
     * Общая защита для всех эффектов: резкий рост средней яркости кадра считается вспышкой.
     * Если лимит вспышек исчерпан, кадр приглушается так, чтобы яркость росла плавно.
     */
    private fun limitFlashes(nowMs: Long) {
        val rgb = canvas.rgb
        val luma = luma(rgb)
        val rise = luma - lastLuma
        if (rise > FLASH_DELTA && !flashLimiter.tryFlash(nowMs)) {
            val allowed = lastLuma + FLASH_DELTA / 4
            // Приглушаем выход, не трогая состояние эффекта на холсте.
            val k = if (luma > 0) allowed * 256 / luma else 256
            for (i in rgb.indices) output[i] = (((rgb[i].toInt() and 0xFF) * k) shr 8).toByte()
            lastLuma = allowed
        } else {
            System.arraycopy(rgb, 0, output, 0, rgb.size)
            lastLuma = luma
        }
    }

    private fun postProcess(brightness: Int, gamma: Boolean) {
        val k = brightness.coerceIn(0, 100) * 256 / 100
        for (i in output.indices) {
            var v = ((output[i].toInt() and 0xFF) * k) shr 8
            if (gamma) v = gammaLut[v]
            output[i] = v.toByte()
        }
    }

    companion object {
        const val GAMMA = 2.2
        /** Рост средней яркости кадра (0..255), который считается вспышкой: ~20 %. */
        const val FLASH_DELTA = 51

        /** Средняя яркость кадра 0..255 (веса Rec. 709 в целых). */
        fun luma(rgb: ByteArray): Int {
            if (rgb.isEmpty()) return 0
            var sum = 0L
            var i = 0
            while (i + 2 < rgb.size) {
                sum += (54 * (rgb[i].toInt() and 0xFF) + 183 * (rgb[i + 1].toInt() and 0xFF) + 19 * (rgb[i + 2].toInt() and 0xFF)) shr 8
                i += 3
            }
            return (sum / (rgb.size / 3)).toInt()
        }
    }
}
