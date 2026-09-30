package com.wledmusic.engine.core.render

/** Встроенные палитры. id устойчивы: сохраняются в настройках. */
enum class PaletteId(val displayName: String, internal val stops: IntArray) {
    RAINBOW("Rainbow", intArrayOf(0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF, 0xFF0000)),
    SUNSET("Sunset", intArrayOf(0x1A0033, 0x7A0060, 0xE0306A, 0xFF7A3D, 0xFFD166)),
    OCEAN("Ocean", intArrayOf(0x001533, 0x003F8A, 0x0096C7, 0x48CAE4, 0xCAF0F8)),
    FOREST("Forest", intArrayOf(0x0B2A12, 0x1B5E20, 0x4CAF50, 0xA5D66A, 0xE8F5A0)),
    FIRE("Fire", intArrayOf(0x000000, 0x800000, 0xFF3000, 0xFF9000, 0xFFF0A0)),
    PARTY("Party", intArrayOf(0x5500AB, 0xB5004B, 0xF4400C, 0xFFB300, 0x5500AB)),
    NEON("Neon", intArrayOf(0x00F5D4, 0x00BBF9, 0x9B5DE5, 0xF15BB5, 0xFEE440)),
    ICE("Ice", intArrayOf(0x0A1A3F, 0x3A6EA5, 0x8FC1E3, 0xFFFFFF)),
}

/** Палитра как таблица из 256 цветов 0xRRGGBB. */
class Palette private constructor(val id: PaletteId, private val lut: IntArray) {
    /** Цвет в позиции 0..255 (значения вне диапазона обрезаются). */
    fun color(position: Int): Int = lut[position.coerceIn(0, 255)]

    /** Цвет в позиции с переносом по кругу (для движущихся градиентов). */
    fun colorWrapped(position: Int): Int = lut[position and 0xFF]

    /** Средний насыщенный цвет палитры — акцент для UI. */
    val accent: Int by lazy {
        var best = lut[128]
        var bestScore = -1
        for (i in 32 until 256 step 16) {
            val c = lut[i]
            val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
            val max = maxOf(r, g, b); val min = minOf(r, g, b)
            val score = (max - min) * 2 + max
            if (score > bestScore) { bestScore = score; best = c }
        }
        best
    }

    companion object {
        private val cache = HashMap<PaletteId, Palette>()

        fun of(id: PaletteId): Palette = synchronized(cache) {
            cache.getOrPut(id) { Palette(id, buildLut(id.stops)) }
        }

        private fun buildLut(stops: IntArray): IntArray = IntArray(256) { i ->
            val pos = i * (stops.size - 1) / 255f
            val a = pos.toInt().coerceAtMost(stops.size - 2)
            lerpColor(stops[a], stops[a + 1], pos - a)
        }
    }
}

internal fun lerpColor(a: Int, b: Int, t: Float): Int {
    val r = ((a shr 16 and 0xFF) + ((b shr 16 and 0xFF) - (a shr 16 and 0xFF)) * t).toInt()
    val g = ((a shr 8 and 0xFF) + ((b shr 8 and 0xFF) - (a shr 8 and 0xFF)) * t).toInt()
    val bl = ((a and 0xFF) + ((b and 0xFF) - (a and 0xFF)) * t).toInt()
    return (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or bl.coerceIn(0, 255)
}

/** Масштабирует цвет на [k] 0..1. */
internal fun scaleColor(c: Int, k: Float): Int {
    if (k >= 1f) return c
    if (k <= 0f) return 0
    val r = ((c shr 16 and 0xFF) * k).toInt()
    val g = ((c shr 8 and 0xFF) * k).toInt()
    val b = ((c and 0xFF) * k).toInt()
    return (r shl 16) or (g shl 8) or b
}
