package com.wledmusic.engine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wledmusic.engine.R
import com.wledmusic.engine.core.dsp.DspConfig
import com.wledmusic.engine.core.network.LatencySnapshot
import com.wledmusic.engine.session.SessionState
import com.wledmusic.engine.session.SyncMode
import com.wledmusic.engine.ui.theme.WmeColors
import kotlin.math.roundToInt

/** Инженерный экран: FFT, параметры DSP (применяются на лету) и задержка по этапам. */
@Composable
fun ProAudioScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val engine = viewModel.engine.collectAsStateValue()
    val session = viewModel.session.collectAsStateValue()
    val features = viewModel.features.collectAsStateWithLifecycle()
    val dsp = engine.dsp

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←") }
            Text("PRO AUDIO", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
        }

        WmeCard {
            InfoRow(stringResource(R.string.label_input), stringResource(R.string.input_internal) + " ●")
            SpectrumBars(features, Modifier.height(96.dp))
            BandMeters(features, precise = true)
        }

        SectionTitle(stringResource(R.string.section_dynamics))
        WmeCard {
            LabeledSlider("Attack", dsp.attackMs, DspConfig.ATTACK_RANGE_MS, "${dsp.attackMs.roundToInt()} ms") { v ->
                viewModel.updateDsp { it.copy(attackMs = v.roundToInt().toFloat()) }
            }
            LabeledSlider("Release", dsp.releaseMs, DspConfig.RELEASE_RANGE_MS, "${dsp.releaseMs.roundToInt()} ms") { v ->
                viewModel.updateDsp { it.copy(releaseMs = (v / 10).roundToInt() * 10f) }
            }
            LabeledSlider("Noise gate", dsp.noiseGateDb, DspConfig.NOISE_GATE_RANGE_DB, "${dsp.noiseGateDb.roundToInt()} dB") { v ->
                viewModel.updateDsp { it.copy(noiseGateDb = v.roundToInt().toFloat()) }
            }
            SwitchRow("Beat detection", dsp.beatDetection) { on -> viewModel.updateDsp { it.copy(beatDetection = on) } }
            SwitchRow("Auto gain", dsp.autoGain) { on -> viewModel.updateDsp { it.copy(autoGain = on) } }
        }

        SectionTitle(stringResource(R.string.section_bands))
        WmeCard {
            BandBounds(viewModel, dsp)
        }
        TextButton(onClick = viewModel::resetDsp) { Text(stringResource(R.string.action_reset)) }

        SectionTitle(stringResource(R.string.section_latency))
        WmeCard { LatencyBlock(session) }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Границы полос: Bass и Mid смежны, High смежна с Mid, поэтому редактируются три точки —
 * граница Bass/Mid, граница Mid/High и верх High. Недопустимая комбинация не принимается.
 */
@Composable
private fun BandBounds(viewModel: MainViewModel, dsp: DspConfig) {
    val bassMid = dsp.bassRange.endInclusive
    val midHigh = dsp.midRange.endInclusive
    Text("Bass   ${hz(dsp.bassRange.start)}–${hz(bassMid)}", fontFamily = FontFamily.Monospace)
    Text("Mid    ${hz(bassMid)}–${hz(midHigh)}", fontFamily = FontFamily.Monospace)
    Text("High   ${hz(midHigh)}–${hz(dsp.highRange.endInclusive)}", fontFamily = FontFamily.Monospace)
    LabeledSlider("Bass / Mid", bassMid, 60f..1_000f, hz(bassMid)) { v ->
        val edge = (v / 10).roundToInt() * 10f
        viewModel.updateDsp { it.copy(bassRange = it.bassRange.start..edge, midRange = edge..it.midRange.endInclusive) }
    }
    LabeledSlider("Mid / High", midHigh, 1_000f..8_000f, hz(midHigh)) { v ->
        val edge = (v / 100).roundToInt() * 100f
        viewModel.updateDsp { it.copy(midRange = it.midRange.start..edge, highRange = edge..it.highRange.endInclusive) }
    }
    LabeledSlider("High max", dsp.highRange.endInclusive, 8_000f..DspConfig.MAX_BAND_HZ, hz(dsp.highRange.endInclusive)) { v ->
        val edge = (v / 500).roundToInt() * 500f
        viewModel.updateDsp { it.copy(highRange = it.highRange.start..edge) }
    }
}

@Composable
private fun LatencyBlock(session: SessionState) {
    val l = session.latency.takeIf { session.isRunning }
    val slow = session.latencyWarning
    LatencyRow("Audio → DSP", l?.audioToDspMs, estimated = l?.audioEstimated == true, highlight = slow == LatencySnapshot.Stage.AUDIO_TO_DSP)
    LatencyRow("DSP → Network", l?.dspToNetworkMs, estimated = false, highlight = slow == LatencySnapshot.Stage.DSP_TO_NETWORK)
    LatencyRow("Network → WLED", l?.networkMs, estimated = true, highlight = slow == LatencySnapshot.Stage.NETWORK)
    LatencyRow("TOTAL", l?.totalMs, estimated = l?.audioEstimated == true || l?.networkMs != null, highlight = slow != null)
    Hint(stringResource(R.string.latency_total_note))
    slow?.let {
        Hint(
            stringResource(
                when (it) {
                    LatencySnapshot.Stage.NETWORK -> R.string.latency_warn_network
                    LatencySnapshot.Stage.DSP_TO_NETWORK -> R.string.latency_warn_dsp
                    LatencySnapshot.Stage.AUDIO_TO_DSP -> R.string.latency_warn_audio
                }
            ),
            WmeColors.Warn,
        )
    }
    if (session.avgDspMs > 0f) InfoRow("DSP", "%.2f ms".format(session.avgDspMs))
    if (session.mode == SyncMode.RGB_ENGINE && session.avgRenderMs > 0f) InfoRow("Render", "%.2f ms".format(session.avgRenderMs))
}

@Composable
private fun LatencyRow(label: String, ms: Float?, estimated: Boolean, highlight: Boolean) {
    Row {
        Text(label, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), color = if (highlight) WmeColors.Warn else MaterialTheme.colorScheme.onSurface)
        Text(
            ms?.let { (if (estimated) "≈ " else "") + "${it.roundToInt()} ms" } ?: "—",
            fontFamily = FontFamily.Monospace,
            color = if (highlight) WmeColors.Warn else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun hz(v: Float): String = if (v >= 1_000f) "%.1f kHz".format(v / 1_000f) else "${v.roundToInt()} Hz"
