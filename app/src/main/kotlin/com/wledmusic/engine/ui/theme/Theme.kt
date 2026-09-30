package com.wledmusic.engine.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object WmeColors {
    val Background = Color(0xFF0D0F12)
    val Surface = Color(0xFF161A1F)
    val SurfaceHigh = Color(0xFF1F242B)
    val Accent = Color(0xFF22D3EE)
    val Bass = Color(0xFFF472B6)
    val Mid = Color(0xFFA78BFA)
    val High = Color(0xFF22D3EE)
    val Ok = Color(0xFF4ADE80)
    val Warn = Color(0xFFFBBF24)
    val Error = Color(0xFFF87171)
    val Muted = Color(0xFF8B949E)
}

private val scheme = darkColorScheme(
    primary = WmeColors.Accent,
    onPrimary = Color(0xFF00161A),
    background = WmeColors.Background,
    onBackground = Color(0xFFE6EDF3),
    surface = WmeColors.Surface,
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = WmeColors.SurfaceHigh,
    onSurfaceVariant = WmeColors.Muted,
    error = WmeColors.Error,
)

/** Dark Minimal: фиксированная тёмная тема независимо от системной. */
@Composable
fun WmeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
