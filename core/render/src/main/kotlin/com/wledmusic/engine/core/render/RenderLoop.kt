package com.wledmusic.engine.core.render

import com.wledmusic.engine.core.dsp.AudioFeatures
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive

/** Кадр предпросмотра: копия RGB-данных и раскладка, в которой их читать. */
class PreviewFrame(val layout: LedLayout, val rgb: ByteArray)

/**
 * Рендер-цикл с фиксированной частотой [fps]: на каждом тике берёт последний кадр признаков
 * (устаревшие не копятся), рисует кадр и отдаёт его в [sink]. Кадры рисуются и без новых
 * признаков — анимации зависят от времени. Вызывать в фоновом диспетчере.
 * [sink] получает кадр, число LED и кадр признаков, по которому он нарисован (для замера задержки).
 *
 * [settings] читается на каждом тике: смена эффекта и параметров применяется со следующего кадра.
 */
class RenderLoop(
    layout: LedLayout,
    private val settings: StateFlow<RenderSettings>,
    private val sink: (rgb: ByteArray, ledCount: Int, features: AudioFeatures?) -> Unit,
    fps: Int = DEFAULT_FPS,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    init {
        require(fps in FPS_RANGE) { "fps must be in $FPS_RANGE" }
    }

    private val renderer = Renderer(layout)
    private val intervalMs = 1000L / fps

    private val _avgRenderMs = MutableStateFlow(0f)
    /** Среднее время рендера кадра, мс; обновляется раз в секунду. */
    val avgRenderMs: StateFlow<Float> = _avgRenderMs.asStateFlow()

    private val _preview = MutableStateFlow<PreviewFrame?>(null)
    /** Прореженная копия кадра (≤ [PREVIEW_FPS] к/с), только пока есть подписчики. */
    val preview: StateFlow<PreviewFrame?> = _preview.asStateFlow()

    suspend fun run(features: StateFlow<AudioFeatures?>) {
        var last = clockNanos()
        val started = last
        var avgNanos = 0.0
        var lastReport = last
        var lastPreview = 0L
        while (currentCoroutineContext().isActive) {
            val now = clockNanos()
            val dtMs = ((now - last) / 1e6).toFloat().coerceIn(0f, MAX_DT_MS)
            last = now
            val input = features.value
            val frame = renderer.render(input, (now - started) / 1_000_000, dtMs, settings.value)
            val renderNanos = clockNanos() - now
            avgNanos = if (avgNanos == 0.0) renderNanos.toDouble() else avgNanos * 0.95 + renderNanos * 0.05
            sink(frame, renderer.layout.ledCount, input)

            if (now - lastReport >= 1_000_000_000L) {
                _avgRenderMs.value = (avgNanos / 1e6).toFloat()
                lastReport = now
            }
            if (_preview.subscriptionCount.value > 0 && now - lastPreview >= 1_000_000_000L / PREVIEW_FPS) {
                _preview.value = PreviewFrame(renderer.layout, frame.copyOf())
                lastPreview = now
            }
            delay(intervalMs)
        }
    }

    companion object {
        const val DEFAULT_FPS = 50
        val FPS_RANGE = 30..60
        const val PREVIEW_FPS = 15
        /** После паузы (например, долгого GC) анимации не «перепрыгивают» дальше 100 мс. */
        const val MAX_DT_MS = 100f
    }
}
