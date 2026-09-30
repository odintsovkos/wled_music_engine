package com.wledmusic.engine.core.render

import com.wledmusic.engine.core.render.effects.AmbientGlow
import com.wledmusic.engine.core.render.effects.BeatFlash
import com.wledmusic.engine.core.render.effects.Flow
import com.wledmusic.engine.core.render.effects.Ripple
import com.wledmusic.engine.core.render.effects.Spectrum
import com.wledmusic.engine.core.render.effects.VuPulse

/**
 * Параметры эффекта. Первый уровень UI: [intensity], [sensitivity], [brightness];
 * «Advanced»: [speed], [palette], [custom] (смысл задаёт эффект, см. [EffectId.customLabel]).
 * Все числовые параметры — 0..100.
 */
data class EffectParams(
    val intensity: Int = 60,
    val sensitivity: Int = 50,
    val brightness: Int = 80,
    val speed: Int = 50,
    val palette: PaletteId = PaletteId.RAINBOW,
    val custom: Int = 50,
) {
    init {
        require(listOf(intensity, sensitivity, brightness, speed, custom).all { it in 0..100 }) { "params must be 0..100" }
    }
}

/** Устойчивые id эффектов: сохраняются в настройках. */
enum class EffectId(val displayName: String, val customLabel: String, val defaultPalette: PaletteId) {
    SPECTRUM("Spectrum", "Удержание пика", PaletteId.RAINBOW),
    VU_PULSE("VU Pulse", "Мягкость края", PaletteId.SUNSET),
    BEAT_FLASH("Beat Flash", "Фон", PaletteId.PARTY),
    RIPPLE("Ripple", "Ширина волны", PaletteId.OCEAN),
    FLOW("Flow", "Насыщенность", PaletteId.NEON),
    AMBIENT_GLOW("Ambient Glow", "Масштаб", PaletteId.FOREST);

    fun defaultParams() = EffectParams(palette = defaultPalette)

    fun create(canvas: Canvas): Effect = when (this) {
        SPECTRUM -> Spectrum(canvas)
        VU_PULSE -> VuPulse(canvas)
        BEAT_FLASH -> BeatFlash(canvas)
        RIPPLE -> Ripple(canvas)
        FLOW -> Flow(canvas)
        AMBIENT_GLOW -> AmbientGlow(canvas)
    }
}

/** Всё, что эффект получает на кадре. Переиспользуется между кадрами. */
class FrameContext(val sound: Sound, val flashLimiter: FlashLimiter) {
    var nowMs: Long = 0; internal set
    var dtMs: Float = 0f; internal set
    var params: EffectParams = EffectParams(); internal set
    var palette: Palette = Palette.of(PaletteId.RAINBOW); internal set
    var direction: AnimationDirection = AnimationDirection.UP; internal set

    /** Intensity 0..100 → 0..1. */
    val intensity: Float get() = params.intensity / 100f
    /** Speed 0..100 → множитель 0.25..4 (50 → 1). */
    val speedFactor: Float get() = Sound.gainFor(params.speed)
    val custom: Float get() = params.custom / 100f
}

/**
 * Аудиореактивный эффект. Экземпляр создаётся под конкретный [Canvas] и хранит своё состояние;
 * [render] вызывается на каждом кадре и не должен выделять память.
 * Для ленты (`!canvas.layout.is2D`) рисуется 1D-вариант, для матрицы — 2D.
 */
interface Effect {
    fun render(ctx: FrameContext)
}
