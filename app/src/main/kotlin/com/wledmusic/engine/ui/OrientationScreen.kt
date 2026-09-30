package com.wledmusic.engine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wledmusic.engine.R
import com.wledmusic.engine.core.render.AnimationDirection
import com.wledmusic.engine.core.render.Canvas
import com.wledmusic.engine.core.render.LedLayout
import com.wledmusic.engine.core.render.PreviewFrame
import com.wledmusic.engine.core.render.Rotation
import com.wledmusic.engine.core.render.TestPattern
import com.wledmusic.engine.ui.theme.WmeColors

/**
 * Ориентация: значения по умолчанию — из автоопределения лампы; ручная коррекция нужна,
 * только если направление на лампе не совпало с физической установкой.
 */
@Composable
fun OrientationScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val ui = viewModel.ui.collectAsStateValue()
    val session = viewModel.session.collectAsStateValue()
    val layout = viewModel.layout.collectAsStateValue()
    viewModel.engine.collectAsStateValue() // перерисовка при смене направления
    val direction = viewModel.direction()
    val previewSource = viewModel.previewSource.collectAsStateValue()
    val preview = previewSource?.collectAsStateValue()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←") }
            Text(
                when (layout) {
                    is LedLayout.Matrix -> stringResource(R.string.orientation_matrix, layout.physicalWidth, layout.physicalHeight)
                    is LedLayout.Strip -> stringResource(R.string.type_strip, layout.ledCount)
                    null -> stringResource(R.string.action_orientation)
                },
                style = MaterialTheme.typography.titleLarge,
            )
        }
        if (layout == null) {
            Hint(stringResource(R.string.start_layout_unknown))
            return@Column
        }

        WmeCard {
            RowOrderDiagram(layout)
            // Превью: живой кадр RGB Engine или тестовый узор, как он ляжет на лампу.
            val frame = preview?.takeIf { it.layout == layout } ?: remember(layout, direction) {
                val canvas = Canvas(layout)
                TestPattern.draw(canvas, direction)
                PreviewFrame(layout, canvas.rgb.copyOf())
            }
            LedPreview(frame, layout)
        }

        when (layout) {
            is LedLayout.Matrix -> MatrixControls(viewModel, layout, direction)
            is LedLayout.Strip -> StripControls(viewModel, layout, direction)
        }

        SizeOverride(viewModel, layout)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = viewModel::showTestPattern,
                enabled = !session.isRunning && !ui.testPatternRunning,
            ) { Text(stringResource(if (ui.testPatternRunning) R.string.test_pattern_running else R.string.action_test_pattern)) }
            TextButton(onClick = viewModel::resetLayout) { Text(stringResource(R.string.action_reset_layout)) }
        }
        if (session.isRunning) Hint(stringResource(R.string.hint_test_pattern_session))
        if (ui.testPatternFailed) Hint(stringResource(R.string.test_pattern_failed), WmeColors.Warn)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun MatrixControls(viewModel: MainViewModel, layout: LedLayout.Matrix, direction: AnimationDirection) {
    SectionTitle(stringResource(R.string.label_rotation))
    ChoiceRow(Rotation.values().toList(), layout.rotation, { "${it.degrees}°" }) {
        viewModel.setLayoutOverride(layout.copy(rotation = it))
    }
    SectionTitle(stringResource(R.string.label_flip))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = layout.flipHorizontal,
            onClick = { viewModel.setLayoutOverride(layout.copy(flipHorizontal = !layout.flipHorizontal)) },
            label = { Text(stringResource(R.string.flip_horizontal)) },
        )
        FilterChip(
            selected = layout.flipVertical,
            onClick = { viewModel.setLayoutOverride(layout.copy(flipVertical = !layout.flipVertical)) },
            label = { Text(stringResource(R.string.flip_vertical)) },
        )
    }
    SwitchRow(stringResource(R.string.label_serpentine), layout.serpentine) {
        viewModel.setLayoutOverride(layout.copy(serpentine = it))
    }
    SectionTitle(stringResource(R.string.label_direction))
    ChoiceRow(AnimationDirection.values().toList(), direction, { directionGlyph(it) }, onSelect = viewModel::setDirection)
}

@Composable
private fun StripControls(viewModel: MainViewModel, layout: LedLayout.Strip, direction: AnimationDirection) {
    SwitchRow(stringResource(R.string.label_reversed), layout.reversed) {
        viewModel.setLayoutOverride(layout.copy(reversed = it))
    }
    SectionTitle(stringResource(R.string.label_direction))
    ChoiceRow(listOf(AnimationDirection.LEFT, AnimationDirection.RIGHT), direction, { directionGlyph(it) }, onSelect = viewModel::setDirection)
}

/** Ручной тип и размер: например, лента, уложенная матрицей, без 2D-настроек в WLED. */
@Composable
private fun SizeOverride(viewModel: MainViewModel, layout: LedLayout) {
    var width by remember(layout) { mutableStateOf(if (layout is LedLayout.Matrix) layout.physicalWidth.toString() else "") }
    var height by remember(layout) { mutableStateOf(if (layout is LedLayout.Matrix) layout.physicalHeight.toString() else "") }
    SectionTitle(stringResource(R.string.label_manual_size))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(width, { width = it.filter(Char::isDigit).take(3) }, Modifier.width(80.dp),
            label = { Text("W") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Text("×")
        OutlinedTextField(height, { height = it.filter(Char::isDigit).take(3) }, Modifier.width(80.dp),
            label = { Text("H") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        val w = width.toIntOrNull()
        val h = height.toIntOrNull()
        val valid = w != null && h != null && w > 0 && h > 0 && w * h <= LedLayout.MAX_LEDS
        OutlinedButton(onClick = { viewModel.setLayoutOverride(LedLayout.Matrix(w!!, h!!)) }, enabled = valid) {
            Text(stringResource(R.string.action_apply))
        }
    }
    val lampLeds = viewModel.lampLedCount()
    if (lampLeds != null && layout.ledCount != lampLeds) {
        Hint(stringResource(R.string.layout_size_mismatch, layout.ledCount, lampLeds), WmeColors.Warn)
    }
}

/** Схема порядка LED: стрелка в каждой клетке указывает на следующий по индексу LED (до 8×8 клеток). */
@Composable
private fun RowOrderDiagram(layout: LedLayout) {
    val w = minOf(layout.width, 8)
    val h = minOf(layout.height, 8)
    Column {
        for (y in 0 until h) {
            Text(
                (0 until w).joinToString(" ") { x -> nextArrow(layout, x, y) },
                fontFamily = FontFamily.Monospace,
                color = if (y == 0) WmeColors.Error else WmeColors.Muted,
            )
        }
    }
}

private fun nextArrow(layout: LedLayout, x: Int, y: Int): String {
    val next = layout.index(x, y) + 1
    return when {
        x + 1 < layout.width && layout.index(x + 1, y) == next -> "→"
        x > 0 && layout.index(x - 1, y) == next -> "←"
        y + 1 < layout.height && layout.index(x, y + 1) == next -> "↓"
        y > 0 && layout.index(x, y - 1) == next -> "↑"
        else -> "·"
    }
}

fun directionGlyph(d: AnimationDirection): String = when (d) {
    AnimationDirection.UP -> "↑"
    AnimationDirection.DOWN -> "↓"
    AnimationDirection.LEFT -> "←"
    AnimationDirection.RIGHT -> "→"
}
