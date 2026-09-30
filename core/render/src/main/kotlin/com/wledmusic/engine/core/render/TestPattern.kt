package com.wledmusic.engine.core.render

/**
 * Тестовый узор для проверки раскладки: диагональ из левого верхнего угла изображения
 * (белая), первая строка (красная), стрелка направления анимации (зелёная) у центра.
 * На ленте: первый LED красный, затем зелёная стрелка по направлению ← или →.
 */
object TestPattern {
    fun draw(canvas: Canvas, direction: AnimationDirection) {
        canvas.clear()
        if (!canvas.layout.is2D) {
            val n = canvas.width
            canvas.set(0, 0, RED)
            val right = direction != AnimationDirection.LEFT
            for (i in 0 until minOf(5, n)) {
                val x = if (right) n / 2 + i else n / 2 - i
                canvas.set(x, 0, scaleColor(GREEN, 0.3f + 0.7f * i / 4f))
            }
            return
        }
        for (x in 0 until canvas.width) canvas.set(x, 0, RED)
        for (i in 0 until minOf(canvas.width, canvas.height)) canvas.set(i, i, WHITE)
        // Стрелка: стержень от центра вперёд и наконечник.
        val along = canvas.alongLength(direction)
        val across = canvas.acrossLength(direction)
        val mid = across / 2
        val start = along / 4
        val end = along * 3 / 4
        for (a in start..end) canvas.setDirected(direction, mid, a, GREEN)
        for (k in 1..2) {
            canvas.setDirected(direction, mid - k, end - k, GREEN)
            canvas.setDirected(direction, mid + k, end - k, GREEN)
        }
    }

    const val RED = 0xFF0000
    const val GREEN = 0x00FF00
    const val WHITE = 0xFFFFFF
}
