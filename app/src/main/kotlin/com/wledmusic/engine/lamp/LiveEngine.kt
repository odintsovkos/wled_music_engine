package com.wledmusic.engine.lamp

import com.wledmusic.engine.core.dsp.DspConfig
import com.wledmusic.engine.core.network.WledAddress
import com.wledmusic.engine.core.render.AnimationDirection
import com.wledmusic.engine.core.render.LedLayout
import com.wledmusic.engine.core.render.RenderSettings
import com.wledmusic.engine.session.LampStatus
import com.wledmusic.engine.settings.EngineSettings
import com.wledmusic.engine.settings.LampSettings

/**
 * Вычисление того, что реально рисует и слушает движок, из сохранённых настроек и данных лампы.
 * Чистые функции: результат публикуется в StateFlow приложения и читается службой на лету.
 */
object LiveEngine {
    /** Раскладка: ручная для этой лампы, иначе автоопределённая; null — неизвестна или > 1024 LED. */
    fun layout(lamp: LampSettings, engine: EngineSettings, status: LampStatus): LedLayout? {
        val host = WledAddress.parse(lamp.host)?.host
        if (engine.layoutOverride != null && engine.layoutHost == host) return engine.layoutOverride
        val info = (status as? LampStatus.Available)?.info ?: return null
        val detected = LedLayout.detect(info.ledCount, info.matrix?.width, info.matrix?.height) ?: return null
        // «Use main segment only»: если основной сегмент короче ленты, realtime идёт только в него.
        val main = status.state?.main
        if (detected is LedLayout.Strip && main != null && main.length in 1 until detected.ledCount) {
            return LedLayout.Strip(main.length)
        }
        return detected
    }

    fun direction(engine: EngineSettings, layout: LedLayout?): AnimationDirection {
        val d = engine.direction
        val is2D = layout?.is2D ?: false
        return when {
            d == null -> if (is2D) AnimationDirection.UP else AnimationDirection.RIGHT
            !is2D && (d == AnimationDirection.UP || d == AnimationDirection.DOWN) -> AnimationDirection.RIGHT
            else -> d
        }
    }

    fun render(engine: EngineSettings, layout: LedLayout?): RenderSettings =
        RenderSettings(engine.effect, engine.paramsFor(engine.effect), direction(engine, layout), engine.gamma)

    fun dsp(engine: EngineSettings): DspConfig = engine.dsp
}
