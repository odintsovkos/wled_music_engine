package com.wledmusic.engine.core.render.effects

import com.wledmusic.engine.core.render.Canvas
import com.wledmusic.engine.core.render.Effect
import com.wledmusic.engine.core.render.FrameContext
import com.wledmusic.engine.core.render.scaleColor
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Ambient Glow: медленно движущийся градиент палитры, яркость «дышит» по сглаженному уровню.
 * Остаётся видимым в тишине (минимальная фоновая яркость). На матрице — диагональный градиент
 * с волнистым искажением.
 */
class AmbientGlow(private val canvas: Canvas) : Effect {
    private var offset = 0f
    private var breath = 0f
    private var clockMs = 0f
    private val sine = FloatArray(256) { sin(it * 2 * PI / 256).toFloat() }

    override fun render(ctx: FrameContext) {
        clockMs += ctx.dtMs
        offset += ctx.dtMs / 1000f * 12f * ctx.speedFactor // позиций палитры в секунду
        if (offset >= 256f) offset -= 256f
        val target = ctx.sound.level
        breath = target + (breath - target) * exp(-ctx.dtMs / 400f)
        val k = (MIN_GLOW + (1f - MIN_GLOW) * (0.35f + 0.65f * ctx.intensity) * breath).coerceIn(0f, 1f)
        val scale = 0.5f + 1.5f * ctx.custom
        val spread = 255f / maxOf(canvas.width, canvas.height) / scale
        val wobblePhase = (clockMs / 40f).toInt()
        for (y in 0 until canvas.height) for (x in 0 until canvas.width) {
            val wobble = if (canvas.layout.is2D) sine[(x * 16 + wobblePhase) and 0xFF] * 12f else 0f
            val pos = ((x + y) * spread + offset + wobble).toInt()
            canvas.set(x, y, scaleColor(ctx.palette.colorWrapped(pos), k))
        }
    }

    companion object {
        /** Минимальная фоновая яркость: лампа не гаснет в тишине. */
        const val MIN_GLOW = 0.2f
    }
}
