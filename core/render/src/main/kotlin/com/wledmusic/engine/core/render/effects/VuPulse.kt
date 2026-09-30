package com.wledmusic.engine.core.render.effects

import com.wledmusic.engine.core.render.Canvas
import com.wledmusic.engine.core.render.Effect
import com.wledmusic.engine.core.render.FrameContext
import com.wledmusic.engine.core.render.scaleColor
import kotlin.math.sqrt

/**
 * VU Pulse. Лента: заливка от центра к краям пропорционально уровню.
 * Матрица: круг от центра, радиус = уровень. Цвет — по расстоянию от центра.
 */
class VuPulse(private val canvas: Canvas) : Effect {
    private val distance: FloatArray
    private val maxDistance: Float

    init {
        val cx = (canvas.width - 1) / 2f
        val cy = (canvas.height - 1) / 2f
        distance = FloatArray(canvas.width * canvas.height) { i ->
            val dx = i % canvas.width - cx
            val dy = i / canvas.width - cy
            sqrt(dx * dx + dy * dy)
        }
        maxDistance = maxOf(0.5f, distance.max()) + 0.5f
    }

    override fun render(ctx: FrameContext) {
        val level = ctx.sound.level
        // Мгновенный уровень поверх сглаженного даёт «удар» без потери плавности.
        val v = (level * 0.7f + ctx.sound.rawLevel * 0.3f) * (0.6f + 0.8f * ctx.intensity)
        val radius = v.coerceIn(0f, 1f) * maxDistance
        val edge = 0.5f + 3f * ctx.custom
        for (y in 0 until canvas.height) for (x in 0 until canvas.width) {
            val d = distance[y * canvas.width + x]
            val k = ((radius - d) / edge + 0.5f).coerceIn(0f, 1f)
            canvas.set(x, y, scaleColor(ctx.palette.color((d / maxDistance * 255).toInt()), k))
        }
    }
}
