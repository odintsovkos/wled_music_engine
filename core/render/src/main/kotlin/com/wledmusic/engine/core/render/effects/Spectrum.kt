package com.wledmusic.engine.core.render.effects

import com.wledmusic.engine.core.render.Canvas
import com.wledmusic.engine.core.render.Effect
import com.wledmusic.engine.core.render.FrameContext
import com.wledmusic.engine.core.render.scaleColor

/**
 * Spectrum. Лента: 16 полос зеркально от центра, яркость = уровень полосы.
 * Матрица: эквалайзер — столбцы по полосам, растут по направлению анимации, с удержанием пика.
 */
class Spectrum(private val canvas: Canvas) : Effect {
    private val peaks = FloatArray(maxOf(canvas.width, canvas.height))
    private val holdMs = FloatArray(peaks.size)

    override fun render(ctx: FrameContext) {
        canvas.clear()
        val bands = ctx.sound.bands
        val gain = 0.5f + ctx.intensity
        if (!canvas.layout.is2D) {
            val n = canvas.width
            val half = (n + 1) / 2
            for (d in 0 until half) {
                val band = d * bands.size / half
                val v = (bands[band] * gain).coerceIn(0f, 1f)
                val c = scaleColor(ctx.palette.color(band * 255 / (bands.size - 1)), v)
                canvas.set(n / 2 + d, 0, c)
                canvas.set((n - 1) / 2 - d, 0, c)
            }
            return
        }
        val dir = ctx.direction
        val across = canvas.acrossLength(dir)
        val along = canvas.alongLength(dir)
        val holdTarget = 100f + 900f * ctx.custom
        val fall = 0.0015f * ctx.speedFactor * ctx.dtMs // доля высоты за кадр
        for (u in 0 until across) {
            val band = u * bands.size / across
            val v = (bands[band] * gain).coerceIn(0f, 1f)
            val height = v * along
            if (height >= peaks[u] * along) {
                peaks[u] = v
                holdMs[u] = holdTarget
            } else if (holdMs[u] > 0f) {
                holdMs[u] -= ctx.dtMs
            } else {
                peaks[u] = (peaks[u] - fall).coerceAtLeast(0f)
            }
            val full = height.toInt()
            for (a in 0 until full) canvas.setDirected(dir, u, a, ctx.palette.color(a * 255 / along))
            val frac = height - full
            if (full < along && frac > 0f) canvas.setDirected(dir, u, full, scaleColor(ctx.palette.color(full * 255 / along), frac))
            val p = (peaks[u] * along).toInt().coerceAtMost(along - 1)
            if (peaks[u] > 0.02f && p >= full) canvas.setDirected(dir, u, p, scaleColor(PEAK_COLOR, ctx.sound.activity))
        }
    }

    private companion object {
        const val PEAK_COLOR = 0xB0B0B0
    }
}
