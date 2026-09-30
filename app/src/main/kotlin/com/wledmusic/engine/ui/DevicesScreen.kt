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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wledmusic.engine.R
import com.wledmusic.engine.core.render.LedLayout
import com.wledmusic.engine.session.LampStatus
import com.wledmusic.engine.session.SyncMode
import com.wledmusic.engine.ui.theme.WmeColors

/** Контроллеры WLED. В Этапе II — одна лампа; действие «Проверить» заменяет её по адресу. */
@Composable
fun DevicesScreen(viewModel: MainViewModel, onOpenOrientation: () -> Unit) {
    val ui = viewModel.ui.collectAsStateValue()
    val session = viewModel.session.collectAsStateValue()
    val engine = viewModel.engine.collectAsStateValue()
    val layout = viewModel.layout.collectAsStateValue()
    val lamp = session.lamp
    val readiness = viewModel.readiness(lamp)
    val locked = session.isRunning

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tab_devices), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp))

        WmeCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                OutlinedTextField(
                    value = ui.host,
                    onValueChange = viewModel::onHostChange,
                    label = { Text(stringResource(R.string.label_host)) },
                    isError = ui.hostError,
                    supportingText = if (ui.hostError) { { Text(stringResource(R.string.error_host)) } } else null,
                    singleLine = true,
                    enabled = !locked,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = viewModel::checkLamp,
                    enabled = ui.hostValid && lamp != LampStatus.Checking && !locked,
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(stringResource(R.string.action_check)) }
            }
            Hint(stringResource(R.string.hint_one_lamp))
        }

        WmeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (lamp as? LampStatus.Available)?.info?.name ?: stringResource(R.string.lamp_default_name),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusDot(lampColor(lamp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (lamp is LampStatus.Available) R.string.lamp_state_online else R.string.lamp_state_offline),
                    color = lampColor(lamp),
                )
            }
            if (lamp !is LampStatus.Available) Text(lampStatusText(lamp), color = lampColor(lamp))
            InfoRow(stringResource(R.string.label_ip), ui.host.ifBlank { "—" })
            (lamp as? LampStatus.Available)?.let { InfoRow(stringResource(R.string.label_version), it.info.version) }
            InfoRow(stringResource(R.string.label_type), layoutTypeText(layout, lamp))
            if (layout is LedLayout.Matrix) {
                InfoRow(stringResource(R.string.label_size), "${layout.physicalWidth} × ${layout.physicalHeight}")
            }
            InfoRow(stringResource(R.string.label_orientation), orientationText(layout, viewModel))
            InfoRow(
                stringResource(R.string.label_effect),
                if (engine.mode == SyncMode.RGB_ENGINE) engine.effect.displayName
                else (lamp as? LampStatus.Available)?.currentEffectName ?: "—",
            )
            OutlinedButton(onClick = onOpenOrientation, enabled = layout != null) {
                Text(stringResource(R.string.action_orientation))
            }
        }

        SectionTitle(stringResource(R.string.label_mode))
        WmeCard {
            ChoiceRow(
                options = SyncMode.values().toList(),
                selected = engine.mode,
                label = { modeLabel(it) },
                enabled = !locked,
                onSelect = viewModel::selectMode,
            )
            if (locked) Hint(stringResource(R.string.hint_mode_locked))
            Readiness(stringResource(R.string.mode_audio_reactive), readiness.audioReactive, R.string.ready_ar_missing)
            Readiness(stringResource(R.string.mode_rgb_engine), readiness.rgbEngine, R.string.ready_rgb_too_many)
            val available = lamp as? LampStatus.Available
            if (engine.mode == SyncMode.AUDIO_REACTIVE && available?.info?.hasAudioReactive == true &&
                available.info.audioSyncReceiveEnabled != true
            ) Hint(stringResource(R.string.lamp_ar_not_receive), WmeColors.Warn)
            if (engine.mode == SyncMode.RGB_ENGINE && (available?.state?.liveOverride ?: 0) != 0) {
                Hint(stringResource(R.string.ready_live_override), WmeColors.Warn)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Readiness(mode: String, ready: Boolean?, reasonRes: Int) {
    val (text, color) = when (ready) {
        true -> stringResource(R.string.ready_yes, mode) to WmeColors.Ok
        false -> "$mode: ${stringResource(reasonRes)}" to WmeColors.Warn
        null -> stringResource(R.string.ready_unknown, mode) to WmeColors.Muted
    }
    Hint(text, color)
}

@Composable
private fun layoutTypeText(layout: LedLayout?, lamp: LampStatus): String = when (layout) {
    is LedLayout.Matrix -> stringResource(R.string.type_matrix)
    is LedLayout.Strip -> stringResource(R.string.type_strip, layout.ledCount)
    null -> if (lamp is LampStatus.Available) stringResource(R.string.type_unsupported) else "—"
}

@Composable
private fun orientationText(layout: LedLayout?, viewModel: MainViewModel): String {
    val arrow = directionGlyph(viewModel.direction())
    return when (layout) {
        is LedLayout.Matrix -> "${layout.rotation.degrees}°" +
            (if (layout.flipHorizontal) " ⇆" else "") + (if (layout.flipVertical) " ⇅" else "") + " · $arrow"
        is LedLayout.Strip -> (if (layout.reversed) "⇆ · " else "") + arrow
        null -> "—"
    }
}
