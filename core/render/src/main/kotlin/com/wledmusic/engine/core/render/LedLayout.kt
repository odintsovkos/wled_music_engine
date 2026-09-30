package com.wledmusic.engine.core.render

/** Поворот изображения при монтаже матрицы, по часовой стрелке. */
enum class Rotation(val degrees: Int) { R0(0), R90(90), R180(180), R270(270) }

/** Направление «вперёд» для направленных эффектов (рост столбцов, движение потока). */
enum class AnimationDirection { UP, DOWN, LEFT, RIGHT }

/**
 * Раскладка светодиодов: отображение логических координат эффекта (x вправо, y вниз, начало —
 * левый верхний угол изображения) в индекс LED в кадре DDP.
 *
 * Матрица: физическая панель [physicalWidth]×[physicalHeight], LED идут строками по
 * [physicalWidth]. Порядок преобразований: отражение → поворот → змейка. При повороте на 90/270°
 * логическое изображение имеет размер [physicalHeight]×[physicalWidth].
 */
sealed class LedLayout {
    abstract val ledCount: Int
    /** Логическая ширина изображения. */
    abstract val width: Int
    /** Логическая высота изображения; для ленты 1. */
    abstract val height: Int
    abstract val is2D: Boolean

    private val table: IntArray by lazy {
        IntArray(width * height) { i -> computeIndex(i % width, i / width) }
    }

    /** Индекс LED для логических координат. */
    fun index(x: Int, y: Int): Int = table[y * width + x]

    protected abstract fun computeIndex(x: Int, y: Int): Int

    data class Strip(
        override val ledCount: Int,
        val reversed: Boolean = false,
    ) : LedLayout() {
        init {
            require(ledCount in 1..MAX_LEDS) { "strip length must be 1..$MAX_LEDS" }
        }
        override val width: Int get() = ledCount
        override val height: Int get() = 1
        override val is2D: Boolean get() = false
        override fun computeIndex(x: Int, y: Int): Int = if (reversed) ledCount - 1 - x else x
    }

    data class Matrix(
        val physicalWidth: Int,
        val physicalHeight: Int,
        val serpentine: Boolean = false,
        val rotation: Rotation = Rotation.R0,
        val flipHorizontal: Boolean = false,
        val flipVertical: Boolean = false,
    ) : LedLayout() {
        init {
            require(physicalWidth >= 1 && physicalHeight >= 1) { "matrix size must be positive" }
            require(physicalWidth * physicalHeight <= MAX_LEDS) { "matrix exceeds $MAX_LEDS LEDs" }
        }
        override val ledCount: Int get() = physicalWidth * physicalHeight
        private val swapped get() = rotation == Rotation.R90 || rotation == Rotation.R270
        override val width: Int get() = if (swapped) physicalHeight else physicalWidth
        override val height: Int get() = if (swapped) physicalWidth else physicalHeight
        override val is2D: Boolean get() = true

        override fun computeIndex(x: Int, y: Int): Int {
            // 1. Отражение в логическом пространстве.
            val lx = if (flipHorizontal) width - 1 - x else x
            val ly = if (flipVertical) height - 1 - y else y
            // 2. Поворот: логическое изображение = физическое, повёрнутое по часовой на rotation.
            val w = physicalWidth
            val h = physicalHeight
            val px: Int
            val py: Int
            when (rotation) {
                Rotation.R0 -> { px = lx; py = ly }
                Rotation.R90 -> { px = ly; py = h - 1 - lx }
                Rotation.R180 -> { px = w - 1 - lx; py = h - 1 - ly }
                Rotation.R270 -> { px = w - 1 - ly; py = lx }
            }
            // 3. Змейка: нечётные физические строки идут справа налево.
            val column = if (serpentine && py % 2 == 1) w - 1 - px else px
            return py * w + column
        }
    }

    companion object {
        const val MAX_LEDS = 1024

        /**
         * Раскладка по данным лампы: при настроенной в WLED 2D-матрице — построчная без змейки
         * (2D-карту применяет прошивка), иначе лента. null — число LED вне 1..[MAX_LEDS].
         */
        fun detect(ledCount: Int?, matrixWidth: Int?, matrixHeight: Int?): LedLayout? {
            if (matrixWidth != null && matrixHeight != null && matrixWidth > 0 && matrixHeight > 0 &&
                matrixWidth * matrixHeight <= MAX_LEDS
            ) return Matrix(matrixWidth, matrixHeight)
            if (ledCount == null || ledCount !in 1..MAX_LEDS) return null
            return Strip(ledCount)
        }
    }
}
