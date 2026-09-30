package com.wledmusic.engine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wledmusic.engine.R
import com.wledmusic.engine.core.render.EffectId
import com.wledmusic.engine.core.render.PaletteId
import com.wledmusic.engine.session.SyncMode
import com.wledmusic.engine.ui.theme.WmeColors

/**
 * Эффекты RGB Engine. Первый уровень — только эффект, Intensity, Sensitivity, Brightness;
 * остальное за «Advanced». Изменения применяются на лету и сохраняются для эффекта.
 */
@Composable
fun EffectsScreen(viewModel: MainViewModel, onOpenDevices: () -> Unit) {
    val engine = viewModel.engine.collectAsStateValue()
    val session = viewModel.session.collectAsStateValue()
    val params = engine.paramsFor(engine.effect)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tab_effects), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp))
        if (engine.mode == SyncMode.AUDIO_REACTIVE) {
            WmeCard {
                Hint(stringResource(R.string.effects_need_rgb), WmeColors.Warn)
                OutlinedButton(onClick = { viewModel.selectMode(SyncMode.RGB_ENGINE) }, enabled = !session.isRunning) {
                    Text(stringResource(R.string.action_switch_rgb))
                }
                if (session.isRunning) Hint(stringResource(R.string.hint_mode_locked))
                TextButton(onClick = onOpenDevices) { Text(stringResource(R.string.tab_devices)) }
            }
        }

        SectionTitle(stringResource(R.string.label_effect))
        WmeCard {
            for (row in EffectId.values().toList().chunked(3)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (effect in row) {
                        FilterChip(
                            selected = effect == engine.effect,
                            onClick = { viewModel.selectEffect(effect) },
                            label = { Text(effect.displayName) },
                        )
                    }
                }
            }
        }

        WmeCard {
            Text(engine.effect.displayName, style = MaterialTheme.typography.titleMedium)
            LabeledSlider(stringResource(R.string.label_intensity), params.intensity.toFloat(), 0f..100f, "${params.intensity}") { v ->
                viewModel.updateParams { it.copy(intensity = v.toInt()) }
            }
            LabeledSlider(stringResource(R.string.label_sensitivity), params.sensitivity.toFloat(), 0f..100f, "${params.sensitivity}") { v ->
                viewModel.updateParams { it.copy(sensitivity = v.toInt()) }
            }
            LabeledSlider(stringResource(R.string.label_brightness), params.brightness.toFloat(), 0f..100f, "${params.brightness}%") { v ->
                viewModel.updateParams { it.copy(brightness = v.toInt()) }
            }
            TextButton(onClick = { viewModel.setAdvancedExpanded(!engine.advancedExpanded) }) {
                Text(stringResource(if (engine.advancedExpanded) R.string.action_advanced_hide else R.string.action_advanced))
            }
            if (engine.advancedExpanded) {
                LabeledSlider(stringResource(R.string.label_speed), params.speed.toFloat(), 0f..100f, "${params.speed}") { v ->
                    viewModel.updateParams { it.copy(speed = v.toInt()) }
                }
                LabeledSlider(engine.effect.customLabel, params.custom.toFloat(), 0f..100f, "${params.custom}") { v ->
                    viewModel.updateParams { it.copy(custom = v.toInt()) }
                }
                Text(stringResource(R.string.label_palette), style = MaterialTheme.typography.bodyLarge)
                for (row in PaletteId.values().toList().chunked(4)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        for (palette in row) {
                            FilterChip(
                                selected = palette == params.palette,
                                onClick = { viewModel.updateParams { it.copy(palette = palette) } },
                                label = { Text(palette.displayName) },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
