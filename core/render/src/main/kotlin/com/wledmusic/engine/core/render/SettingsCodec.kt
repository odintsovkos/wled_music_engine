package com.wledmusic.engine.core.render

/**
 * Строковые форматы для хранения параметров рендера в DataStore.
 * Неизвестные или повреждённые значения декодируются в null — вызывающий берёт значения по умолчанию.
 */
object SettingsCodec {
    /** `intensity,sensitivity,brightness,speed,PALETTE,custom`. */
    fun encodeParams(p: EffectParams): String =
        listOf(p.intensity, p.sensitivity, p.brightness, p.speed, p.palette.name, p.custom).joinToString(",")

    fun decodeParams(value: String?): EffectParams? {
        val parts = value?.split(',') ?: return null
        if (parts.size != 6) return null
        val numbers = listOf(0, 1, 2, 3, 5).map { parts[it].toIntOrNull()?.takeIf { v -> v in 0..100 } ?: return null }
        val palette = PaletteId.values().firstOrNull { it.name == parts[4] } ?: return null
        return EffectParams(numbers[0], numbers[1], numbers[2], numbers[3], palette, numbers[4])
    }

    /** `strip:N:reversed` или `matrix:W:H:serpentine:R90:flipH:flipV`. */
    fun encodeLayout(layout: LedLayout): String = when (layout) {
        is LedLayout.Strip -> "strip:${layout.ledCount}:${layout.reversed}"
        is LedLayout.Matrix -> listOf(
            "matrix", layout.physicalWidth, layout.physicalHeight, layout.serpentine,
            layout.rotation.name, layout.flipHorizontal, layout.flipVertical,
        ).joinToString(":")
    }

    fun decodeLayout(value: String?): LedLayout? {
        val parts = value?.split(':') ?: return null
        return runCatching {
            when (parts[0]) {
                "strip" -> LedLayout.Strip(parts[1].toInt(), parts[2].toBooleanStrict())
                "matrix" -> LedLayout.Matrix(
                    parts[1].toInt(), parts[2].toInt(), parts[3].toBooleanStrict(),
                    Rotation.valueOf(parts[4]), parts[5].toBooleanStrict(), parts[6].toBooleanStrict(),
                )
                else -> null
            }
        }.getOrNull()
    }
}
