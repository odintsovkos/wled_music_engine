package com.wledmusic.engine.core.render

/**
 * Ограничение частоты вспышек: не более [maxFlashes] резких повышений яркости кадра в любую
 * скользящую секунду (WCAG 2.3.1 — не более трёх вспышек в секунду).
 */
class FlashLimiter(private val maxFlashes: Int = 3, private val windowMs: Long = 1_000) {
    private val times = LongArray(maxFlashes) { Long.MIN_VALUE / 2 }
    private var next = 0

    /** Можно ли вспыхнуть в момент [nowMs], не расходуя разрешение. */
    fun canFlash(nowMs: Long): Boolean = nowMs - times[next] >= windowMs

    /** Регистрирует вспышку, если она разрешена; `true` — разрешена. */
    fun tryFlash(nowMs: Long): Boolean {
        if (!canFlash(nowMs)) return false
        times[next] = nowMs
        next = (next + 1) % times.size
        return true
    }

    fun reset() {
        times.fill(Long.MIN_VALUE / 2)
        next = 0
    }
}
