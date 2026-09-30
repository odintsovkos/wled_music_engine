package com.wledmusic.engine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wledmusic.engine.R
import com.wledmusic.engine.core.network.AudioSyncSender
import com.wledmusic.engine.core.render.RenderLoop

/** Редко меняемые параметры: всё инженерное — здесь или в Pro Audio, но не на главном экране. */
@Composable
fun SettingsScreen(viewModel: MainViewModel, onOpenProAudio: () -> Unit) {
    val ui = viewModel.ui.collectAsStateValue()
    val engine = viewModel.engine.collectAsStateValue()
    val session = viewModel.session.collectAsStateValue()
    val locked = session.isRunning

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp))
        WmeCard(onClick = onOpenProAudio) {
            Text("Pro Audio", style = MaterialTheme.typography.titleMedium)
            Hint(stringResource(R.string.pro_audio_hint))
        }

        SectionTitle(stringResource(R.string.mode_rgb_engine))
        WmeCard {
            LabeledSlider(
                stringResource(R.string.label_fps), engine.fps.toFloat(),
                RenderLoop.FPS_RANGE.first.toFloat()..RenderLoop.FPS_RANGE.last.toFloat(), "${engine.fps}/s", enabled = !locked,
            ) { viewModel.setFps(it.toInt()) }
            SwitchRow(stringResource(R.string.label_gamma), engine.gamma) { viewModel.setGamma(it) }
            Hint(stringResource(R.string.gamma_hint))
            if (locked) Hint(stringResource(R.string.hint_applies_next_session))
        }

        SectionTitle(stringResource(R.string.mode_audio_reactive))
        WmeCard {
            LabeledSlider(
                stringResource(R.string.label_packet_rate), ui.packetsPerSecond.toFloat(),
                AudioSyncSender.RATE_RANGE.first.toFloat()..AudioSyncSender.RATE_RANGE.last.toFloat(),
                "${ui.packetsPerSecond}/s", enabled = !locked,
            ) { viewModel.onRateChange(it.toInt()) }
            OutlinedTextField(
                value = ui.port,
                onValueChange = viewModel::onPortChange,
                label = { Text(stringResource(R.string.label_port)) },
                isError = ui.portError,
                supportingText = if (ui.portError) { { Text(stringResource(R.string.error_port)) } } else null,
                singleLine = true,
                enabled = !locked,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(160.dp),
            )
        }

        SectionTitle(stringResource(R.string.section_privacy))
        WmeCard { Hint(stringResource(R.string.privacy_text)) }

        val version = LocalContext.current.let { it.packageManager.getPackageInfo(it.packageName, 0).versionName }
        Hint(stringResource(R.string.version, version ?: "—"))
        Spacer(Modifier.height(16.dp))
    }
}
