package com.wledmusic.engine.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Графитовая тёмная тема: не чисто чёрный фон, поверхности отделяются тоном, а не рамками. */
object WmeColors {
    val Background = Color(0xFF1B1E23)
    val Surface = Color(0xFF23272D)
    val SurfaceHigh = Color(0xFF2D3239)
    val OnBackground = Color(0xFFE8ECF1)
    val Accent = Color(0xFF5CC8D8)
    val Bass = Color(0xFFF08BB8)
    val Mid = Color(0xFFB39DF2)
    val High = Color(0xFF5CC8D8)
    val Ok = Color(0xFF6FD69A)
    val Warn = Color(0xFFF2C86B)
    val Error = Color(0xFFF08A8A)
    /** Вторичный текст: контраст к Background ≈ 6:1 (WCAG AA). */
    val Muted = Color(0xFF9EA7B3)
}

/** Акцент активной палитры (эффекта RGB Engine или цвета сегмента WLED). */
val LocalAccent = staticCompositionLocalOf { WmeColors.Accent }

/**
 * Приводит цвет палитры к акценту, читаемому на графитовом фоне (контраст ≥ 4.5:1):
 * тёмные цвета смешиваются с белым.
 */
fun readableAccent(rgb: Int?): Color {
    if (rgb == null) return WmeColors.Accent
    var color = Color(0xFF000000.toInt() or rgb)
    val bg = WmeColors.Background.luminance()
    var i = 0
    while ((color.luminance() + 0.05f) / (bg + 0.05f) < 4.5f && i < 10) {
        color = Color(
            red = color.red + (1f - color.red) * 0.2f,
            green = color.green + (1f - color.green) * 0.2f,
            blue = color.blue + (1f - color.blue) * 0.2f,
        )
        i++
    }
    return color
}

private val typography = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
        titleMedium = t.titleMedium.copy(fontSize = 18.sp),
    )
}

/** Крупный шрифт статусов и кнопки старта. */
val StatusTextStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium)

/** Фиксированная тёмная тема независимо от системной. */
@Composable
fun WmeTheme(accent: Color = WmeColors.Accent, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = if (accent.luminance() > 0.4f) Color(0xFF111418) else Color.White,
        background = WmeColors.Background,
        onBackground = WmeColors.OnBackground,
        surface = WmeColors.Surface,
        onSurface = WmeColors.OnBackground,
        surfaceVariant = WmeColors.SurfaceHigh,
        onSurfaceVariant = WmeColors.Muted,
        surfaceContainer = WmeColors.Surface,
        error = WmeColors.Error,
    )
    CompositionLocalProvider(LocalAccent provides accent) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
