package com.wledmusic.engine.core.render.effects

import com.wledmusic.engine.core.render.AnimationDirection
import com.wledmusic.engine.core.render.Canvas
import com.wledmusic.engine.core.render.Effect
import com.wledmusic.engine.core.render.FrameContext
import com.wledmusic.engine.core.render.scaleColor
import kotlin.math.ln

/**
 * Flow. Лента: цвет доминирующей частоты «втекает» с начала и течёт по направлению анимации.
 * Матрица: водопад-спектрограмма — новая строка полос появляется в начале и сдвигается вперёд.
 */
class Flow(private val canvas: Canvas) : Effect {
    // История по оси «вперёд»: [along][across], цвет 0xRRGGBB. Кольцевой буфер строк.
    private val maxAlong = maxOf(canvas.width, canvas.height)
    private val maxAcross = maxOf(canvas.width, canvas.height)
    private val history = IntArray(maxAlong * maxAcross)
    private var head = 0
    private var accumulator = 0f

    override fun render(ctx: FrameContext) {
        val dir = if (canvas.layout.is2D) ctx.direction else horizontal(ctx)
        val along = canvas.alongLength(dir)
        val across = canvas.acrossLength(dir)
        val rowsPerSecond = (if (canvas.layout.is2D) 12f else 30f) * ctx.speedFactor
        accumulator += rowsPerSecond * ctx.dtMs / 1000f
        while (accumulator >= 1f) {
            accumulator -= 1f
            head = (head + maxAlong - 1) % maxAlong
            writeRow(ctx, head, across)
        }
        // В тишине история водопада гаснет вместе с огибающей звука, а не «вытекает» ещё секунду.
        val fade = ctx.sound.activity
        for (a in 0 until along) {
            val row = (head + a) % maxAlong
            for (u in 0 until across) canvas.setDirected(dir, u, a, scaleColor(history[row * maxAcross + u], fade))
        }
    }

    private fun writeRow(ctx: FrameContext, row: Int, across: Int) {
        val s = ctx.sound
        val strength = 0.4f + 0.6f * ctx.intensity
        if (!canvas.layout.is2D) {
            val pos = hzToPosition(s.majorPeakHz)
            val v = (s.level * 0.6f + s.rawLevel * 0.4f) * strength
            history[row * maxAcross] = scaleColor(saturate(ctx.palette.color(pos), ctx.custom), v.coerceIn(0f, 1f))
            return
        }
        for (u in 0 until across) {
            val band = u * s.bands.size / across
            val v = (s.bands[band] * (0.5f + strength)).coerceIn(0f, 1f)
            history[row * maxAcross + u] = scaleColor(saturate(ctx.palette.color((v * 255).toInt()), ctx.custom), v)
        }
    }

    /** Для ленты годятся только ← и →; остальные трактуются как →. */
    private fun horizontal(ctx: FrameContext) =
        if (ctx.direction == AnimationDirection.LEFT) AnimationDirection.LEFT else AnimationDirection.RIGHT

    /** Смешивание с белым при низкой насыщенности (custom < 0.5). */
    private fun saturate(color: Int, amount: Float): Int {
        if (amount >= 0.5f) return color
        val t = (0.5f - amount) * 2f * 0.6f
        val r = color shr 16 and 0xFF; val g = color shr 8 and 0xFF; val b = color and 0xFF
        return ((r + ((255 - r) * t).toInt()) shl 16) or ((g + ((255 - g) * t).toInt()) shl 8) or (b + ((255 - b) * t).toInt())
    }

    private fun hzToPosition(hz: Float): Int {
        if (hz <= MIN_HZ) return 0
        return ((ln(hz / MIN_HZ) / ln(MAX_HZ / MIN_HZ)) * 255f).toInt().coerceIn(0, 255)
    }

    private companion object {
        const val MIN_HZ = 40f
        const val MAX_HZ = 8_000f
    }
}
