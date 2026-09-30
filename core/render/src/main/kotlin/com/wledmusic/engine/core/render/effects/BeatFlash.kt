package com.wledmusic.engine.core.render.effects

import com.wledmusic.engine.core.render.Canvas
import com.wledmusic.engine.core.render.Effect
import com.wledmusic.engine.core.render.FrameContext
import com.wledmusic.engine.core.render.scaleColor
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Beat Flash: на акцент — вспышка следующим цветом палитры с экспоненциальным затуханием.
 * На матрице вспышка ярче в центре. Частоту вспышек ограничивает [FrameContext.flashLimiter].
 */
class BeatFlash(private val canvas: Canvas) : Effect {
    private var flash = 0f
    private var colorPos = 0
    private val falloff: FloatArray

    init {
        val cx = (canvas.width - 1) / 2f
        val cy = (canvas.height - 1) / 2f
        val max = sqrt(cx * cx + cy * cy).coerceAtLeast(1f)
        falloff = FloatArray(canvas.width * canvas.height) { i ->
            if (!canvas.layout.is2D) 1f else {
                val dx = i % canvas.width - cx
                val dy = i / canvas.width - cy
                1f - 0.6f * sqrt(dx * dx + dy * dy) / max
            }
        }
    }

    override fun render(ctx: FrameContext) {
        if (ctx.sound.beat && ctx.flashLimiter.canFlash(ctx.nowMs)) {
            flash = 1f
            colorPos = (colorPos + 40) and 0xFF
        } else {
            val tau = 250f / ctx.speedFactor
            flash *= exp(-ctx.dtMs / tau)
        }
        val background = 0.25f * ctx.custom * (0.3f + 0.7f * ctx.sound.level)
        val strength = 0.3f + 0.7f * ctx.intensity
        val color = ctx.palette.colorWrapped(colorPos)
        for (y in 0 until canvas.height) for (x in 0 until canvas.width) {
            val f = falloff[y * canvas.width + x]
            val k = background + flash * strength * f
            canvas.set(x, y, scaleColor(color, k.coerceIn(0f, 1f)))
        }
    }
}
