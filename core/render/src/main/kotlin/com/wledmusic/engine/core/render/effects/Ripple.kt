package com.wledmusic.engine.core.render.effects

import com.wledmusic.engine.core.render.Canvas
import com.wledmusic.engine.core.render.Effect
import com.wledmusic.engine.core.render.FrameContext
import com.wledmusic.engine.core.render.scaleColor
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Ripple: акцент порождает волну из случайной точки (лента) или кольцо (матрица).
 * Одновременно не более [MAX_WAVES] волн; новая вытесняет самую старую.
 */
class Ripple(private val canvas: Canvas, seed: Int = 7) : Effect {
    private val random = Random(seed)
    private val cx = FloatArray(MAX_WAVES)
    private val cy = FloatArray(MAX_WAVES)
    private val age = FloatArray(MAX_WAVES) { Float.MAX_VALUE }
    private val amp = FloatArray(MAX_WAVES)
    private val colorPos = IntArray(MAX_WAVES)
    private var nextColor = 0
    private val span = maxOf(canvas.width, canvas.height).toFloat()

    /** Число «живых» волн (для тестов и диагностики). */
    val activeWaves: Int get() = age.count { it < lifetimeMs }
    private var lifetimeMs = 1_500f

    override fun render(ctx: FrameContext) {
        val speed = span / 1.2f * ctx.speedFactor // пикселей в секунду
        lifetimeMs = span / speed * 1000f
        if (ctx.sound.beat) spawn(0.5f + 0.5f * ctx.intensity * (0.5f + ctx.sound.level))
        for (i in 0 until MAX_WAVES) age[i] += ctx.dtMs

        val width = 0.8f + 2.5f * ctx.custom
        val ambient = 0.08f * ctx.sound.level
        val bg = scaleColor(ctx.palette.color(128), ambient)
        for (y in 0 until canvas.height) for (x in 0 until canvas.width) {
            canvas.set(x, y, bg)
            for (i in 0 until MAX_WAVES) {
                if (age[i] >= lifetimeMs) continue
                val r = age[i] / 1000f * speed
                val dx = x - cx[i]
                val dy = y - cy[i]
                val d = if (canvas.layout.is2D) sqrt(dx * dx + dy * dy) else abs(dx)
                val ring = 1f - abs(d - r) / width
                if (ring <= 0f) continue
                val life = 1f - age[i] / lifetimeMs
                canvas.add(x, y, scaleColor(ctx.palette.colorWrapped(colorPos[i]), ring * life * amp[i]))
            }
        }
    }

    private fun spawn(amplitude: Float) {
        var slot = 0
        for (i in 1 until MAX_WAVES) if (age[i] > age[slot]) slot = i
        cx[slot] = random.nextInt(canvas.width).toFloat()
        cy[slot] = if (canvas.layout.is2D) random.nextInt(canvas.height).toFloat() else 0f
        age[slot] = 0f
        amp[slot] = amplitude.coerceIn(0f, 1f)
        nextColor = (nextColor + 37) and 0xFF
        colorPos[slot] = nextColor
    }

    companion object {
        const val MAX_WAVES = 8
    }
}
