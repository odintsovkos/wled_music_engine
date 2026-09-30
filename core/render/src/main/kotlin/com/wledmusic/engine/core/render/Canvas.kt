package com.wledmusic.engine.core.render

/**
 * Холст эффекта: логические координаты [LedLayout] поверх RGB-буфера кадра (3 байта на LED,
 * порядок LED — как в DDP). Буфер выделяется один раз; содержимое сохраняется между кадрами,
 * поэтому эффекты могут затухать через [fade].
 */
class Canvas(val layout: LedLayout) {
    val width: Int get() = layout.width
    val height: Int get() = layout.height
    val rgb = ByteArray(layout.ledCount * 3)

    fun clear() = rgb.fill(0)

    fun set(x: Int, y: Int, color: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        val o = layout.index(x, y) * 3
        rgb[o] = (color shr 16).toByte()
        rgb[o + 1] = (color shr 8).toByte()
        rgb[o + 2] = color.toByte()
    }

    /** Складывает цвет с насыщением. */
    fun add(x: Int, y: Int, color: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        val o = layout.index(x, y) * 3
        rgb[o] = minOf(255, (rgb[o].toInt() and 0xFF) + (color shr 16 and 0xFF)).toByte()
        rgb[o + 1] = minOf(255, (rgb[o + 1].toInt() and 0xFF) + (color shr 8 and 0xFF)).toByte()
        rgb[o + 2] = minOf(255, (rgb[o + 2].toInt() and 0xFF) + (color and 0xFF)).toByte()
    }

    fun get(x: Int, y: Int): Int {
        val o = layout.index(x, y) * 3
        return ((rgb[o].toInt() and 0xFF) shl 16) or ((rgb[o + 1].toInt() and 0xFF) shl 8) or (rgb[o + 2].toInt() and 0xFF)
    }

    /** Умножает весь кадр на [factor] 0..1. */
    fun fade(factor: Float) {
        val k = (factor.coerceIn(0f, 1f) * 256).toInt()
        for (i in rgb.indices) rgb[i] = (((rgb[i].toInt() and 0xFF) * k) shr 8).toByte()
    }

    fun fill(color: Int) {
        for (i in 0 until layout.ledCount) {
            rgb[i * 3] = (color shr 16).toByte()
            rgb[i * 3 + 1] = (color shr 8).toByte()
            rgb[i * 3 + 2] = color.toByte()
        }
    }

    /** Длина оси «вперёд» для [direction]. */
    fun alongLength(direction: AnimationDirection): Int = when (direction) {
        AnimationDirection.UP, AnimationDirection.DOWN -> height
        AnimationDirection.LEFT, AnimationDirection.RIGHT -> width
    }

    /** Длина поперечной оси для [direction]. */
    fun acrossLength(direction: AnimationDirection): Int = when (direction) {
        AnimationDirection.UP, AnimationDirection.DOWN -> width
        AnimationDirection.LEFT, AnimationDirection.RIGHT -> height
    }

    /**
     * Рисует в осях направления: [along] — расстояние от «начала» вдоль [direction],
     * [across] — поперечная координата.
     */
    fun setDirected(direction: AnimationDirection, across: Int, along: Int, color: Int) = when (direction) {
        AnimationDirection.UP -> set(across, height - 1 - along, color)
        AnimationDirection.DOWN -> set(across, along, color)
        AnimationDirection.RIGHT -> set(along, across, color)
        AnimationDirection.LEFT -> set(width - 1 - along, across, color)
    }
}
