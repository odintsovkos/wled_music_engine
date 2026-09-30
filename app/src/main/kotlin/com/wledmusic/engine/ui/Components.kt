package com.wledmusic.engine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wledmusic.engine.core.dsp.AudioFeatures
import com.wledmusic.engine.core.render.LedLayout
import com.wledmusic.engine.core.render.PreviewFrame
import com.wledmusic.engine.ui.theme.LocalAccent
import com.wledmusic.engine.ui.theme.WmeColors

/** Карточка без обводки: отделяется от фона тоном поверхности. */
@Composable
fun WmeCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth().then(if (onClick != null) Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = WmeColors.Muted, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun Hint(text: String, color: Color = WmeColors.Muted) {
    Text(text, color = color, style = MaterialTheme.typography.bodySmall)
}

@Composable
fun StatusDot(color: Color, size: Int = 10) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

/** Строка «подпись — значение». */
@Composable
fun InfoRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = WmeColors.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Ползунок с подписью и значением над ним. */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    enabled: Boolean = true,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = WmeColors.Muted, fontFamily = FontFamily.Monospace)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps, enabled = enabled)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Ряд выбора одного значения из нескольких. */
@Composable
fun <T> ChoiceRow(options: List<T>, selected: T?, label: @Composable (T) -> String, enabled: Boolean = true, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (option in options) {
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) }, enabled = enabled)
        }
    }
}

/**
 * Компактный спектр: 16 полос. Читает [features] только внутри Canvas,
 * чтобы частые обновления не перерисовывали экран целиком.
 */
@Composable
fun SpectrumBars(features: State<AudioFeatures?>, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Canvas(modifier.fillMaxWidth()) {
        val bands = features.value?.bands
        val count = bands?.size ?: 16
        val gap = 4.dp.toPx()
        val barWidth = (size.width - gap * (count - 1)) / count
        val radius = CornerRadius(3.dp.toPx())
        for (i in 0 until count) {
            val x = i * (barWidth + gap)
            drawRoundRect(WmeColors.SurfaceHigh, Offset(x, 0f), Size(barWidth, size.height), radius)
            val v = bands?.get(i) ?: 0f
            if (v > 0f) {
                val h = size.height * v
                drawRoundRect(accent.copy(alpha = 0.55f + 0.45f * v), Offset(x, size.height - h), Size(barWidth, h), radius)
            }
        }
    }
}

/** Три индикатора Bass / Mid / High: подпись, процент, полоска. */
@Composable
fun BandMeters(features: State<AudioFeatures?>, precise: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf("BASS" to WmeColors.Bass, "MID" to WmeColors.Mid, "HIGH" to WmeColors.High).forEachIndexed { index, (label, color) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = WmeColors.Muted)
                BandValue(features, index, precise)
                Canvas(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(WmeColors.SurfaceHigh)) {
                    val f = features.value ?: return@Canvas
                    val v = when (index) { 0 -> f.bass; 1 -> f.mid; else -> f.high }
                    drawRect(color, size = Size(size.width * v, size.height))
                }
            }
        }
    }
}

@Composable
private fun BandValue(features: State<AudioFeatures?>, index: Int, precise: Boolean) {
    val f = features.value
    val v = when (index) { 0 -> f?.bass; 1 -> f?.mid; else -> f?.high } ?: 0f
    Text(
        if (precise) "%.2f".format(v) else "${(v * 100).toInt()}%",
        style = MaterialTheme.typography.titleMedium,
        fontFamily = FontFamily.Monospace,
    )
}

/** Предпросмотр кадра RGB Engine в логических координатах раскладки. */
@Composable
fun LedPreview(frame: PreviewFrame?, layout: LedLayout?, modifier: Modifier = Modifier) {
    val l = frame?.layout ?: layout ?: return
    val aspect = l.width.toFloat() / l.height
    Canvas(modifier.fillMaxWidth().height(if (l.is2D) (220f / maxOf(1f, aspect)).coerceIn(80f, 260f).dp else 24.dp)) {
        val cell = minOf(size.width / l.width, size.height / l.height)
        val dot = cell * 0.8f
        val left = (size.width - cell * l.width) / 2
        val top = (size.height - cell * l.height) / 2
        for (y in 0 until l.height) for (x in 0 until l.width) {
            val color = frame?.let {
                val o = l.index(x, y) * 3
                Color(it.rgb[o].toInt() and 0xFF, it.rgb[o + 1].toInt() and 0xFF, it.rgb[o + 2].toInt() and 0xFF)
            } ?: WmeColors.SurfaceHigh
            drawRoundRect(
                if (color == Color.Black) WmeColors.SurfaceHigh else color,
                Offset(left + x * cell + (cell - dot) / 2, top + y * cell + (cell - dot) / 2),
                Size(dot, dot), CornerRadius(dot / 4),
            )
        }
    }
}
