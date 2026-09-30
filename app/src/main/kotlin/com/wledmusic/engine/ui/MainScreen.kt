package com.wledmusic.engine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wledmusic.engine.R
import com.wledmusic.engine.core.dsp.AudioFeatures
import com.wledmusic.engine.core.network.AudioSyncSender
import com.wledmusic.engine.core.network.WledInfoResult
import com.wledmusic.engine.service.SyncNotification
import com.wledmusic.engine.session.CaptureStatus
import com.wledmusic.engine.session.LampStatus
import com.wledmusic.engine.session.SessionState
import com.wledmusic.engine.session.TransportStatus
import com.wledmusic.engine.ui.theme.WmeColors

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    showPermissionRationale: Boolean,
    notificationsDenied: Boolean,
    onPermissionRationaleContinue: () -> Unit,
    onPermissionRationaleDismiss: () -> Unit,
    onStop: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    // Высокочастотное состояние читается только внутри индикаторов.
    val features = viewModel.features.collectAsStateWithLifecycle()

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                Header(session)
                CaptureCard(session, features, notificationsDenied)
                LampCard(ui, session, viewModel, enabled = !session.isRunning)
                TransportCard(session)
            }
            StartStopButton(
                running = session.isRunning,
                enabled = session.isRunning || ui.canStart,
                onStart = viewModel::requestStart,
                onStop = onStop,
            )
        }
    }

    if (ui.confirmPublicAddress) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPublicAddress,
            title = { Text(stringResource(R.string.public_title)) },
            text = { Text(stringResource(R.string.public_text, ui.host)) },
            confirmButton = { TextButton(onClick = viewModel::confirmPublicAddress) { Text(stringResource(R.string.action_send_anyway)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissPublicAddress) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (showPermissionRationale) {
        AlertDialog(
            onDismissRequest = onPermissionRationaleDismiss,
            title = { Text(stringResource(R.string.permission_title)) },
            text = { Text(stringResource(R.string.permission_text)) },
            confirmButton = { TextButton(onClick = onPermissionRationaleContinue) { Text(stringResource(R.string.action_continue)) } },
            dismissButton = { TextButton(onClick = onPermissionRationaleDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Header(session: SessionState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        StatusDot(captureColor(session.capture))
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = WmeColors.Muted)
            content()
        }
    }
}

@Composable
private fun CaptureCard(session: SessionState, features: State<AudioFeatures?>, notificationsDenied: Boolean) {
    Card(stringResource(R.string.section_capture)) {
        Text(
            stringResource(SyncNotification.captureLabel(session.capture)),
            color = captureColor(session.capture),
            style = MaterialTheme.typography.titleMedium,
        )
        if (session.captureBlockedSuspected) Hint(stringResource(R.string.hint_capture_blocked), WmeColors.Warn)
        if (session.capture == CaptureStatus.STOPPED_BY_SYSTEM) {
            Hint(stringResource(R.string.hint_stopped_by_system), WmeColors.Error)
            session.stopReason?.let { Hint(stringResource(R.string.stop_reason, it), WmeColors.Muted) }
        }
        if (notificationsDenied) Hint(stringResource(R.string.hint_notifications_denied), WmeColors.Muted)
        LevelMeter(features)
        SpectrumBars(features)
        BandMeters(features)
    }
}

@Composable
private fun LevelMeter(features: State<AudioFeatures?>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.label_level), style = MaterialTheme.typography.bodySmall, color = WmeColors.Muted, modifier = Modifier.width(64.dp))
        Canvas(
            Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(WmeColors.SurfaceHigh)
        ) {
            val f = features.value ?: return@Canvas
            drawRect(WmeColors.Accent, size = Size(size.width * f.level, size.height))
            if (f.peak) drawRect(Color.White, topLeft = Offset(size.width * f.level - 3.dp.toPx(), 0f), size = Size(3.dp.toPx(), size.height))
        }
    }
}

@Composable
private fun SpectrumBars(features: State<AudioFeatures?>) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
    ) {
        val bands = features.value?.bands
        val count = bands?.size ?: 16
        val gap = 3.dp.toPx()
        val barWidth = (size.width - gap * (count - 1)) / count
        for (i in 0 until count) {
            val x = i * (barWidth + gap)
            drawRoundRect(WmeColors.SurfaceHigh, Offset(x, 0f), Size(barWidth, size.height), CornerRadius(2.dp.toPx()))
            val v = bands?.get(i) ?: 0f
            if (v > 0f) {
                val h = size.height * v
                val color = lerpColor(WmeColors.Bass, WmeColors.High, i / (count - 1f))
                drawRoundRect(color, Offset(x, size.height - h), Size(barWidth, h), CornerRadius(2.dp.toPx()))
            }
        }
    }
}

@Composable
private fun BandMeters(features: State<AudioFeatures?>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("BASS" to WmeColors.Bass, "MID" to WmeColors.Mid, "HIGH" to WmeColors.High).forEachIndexed { index, (label, color) ->
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = WmeColors.Muted, fontFamily = FontFamily.Monospace)
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(WmeColors.SurfaceHigh)
                ) {
                    val f = features.value ?: return@Canvas
                    val v = when (index) { 0 -> f.bass; 1 -> f.mid; else -> f.high }
                    drawRect(color, size = Size(size.width * v, size.height))
                }
            }
        }
    }
}

@Composable
private fun LampCard(ui: MainUiState, session: SessionState, viewModel: MainViewModel, enabled: Boolean) {
    Card(stringResource(R.string.section_lamp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = ui.host,
                onValueChange = viewModel::onHostChange,
                label = { Text(stringResource(R.string.label_host)) },
                isError = ui.hostError,
                supportingText = if (ui.hostError) { { Text(stringResource(R.string.error_host)) } } else null,
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = ui.port,
                onValueChange = viewModel::onPortChange,
                label = { Text(stringResource(R.string.label_port)) },
                isError = ui.portError,
                supportingText = if (ui.portError) { { Text(stringResource(R.string.error_port)) } } else null,
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(104.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { LampStatusText(session.lamp) }
            OutlinedButton(onClick = viewModel::checkLamp, enabled = ui.hostValid && session.lamp != LampStatus.Checking) {
                Text(stringResource(R.string.action_check))
            }
        }
        Text(stringResource(R.string.label_rate, ui.packetsPerSecond), style = MaterialTheme.typography.bodySmall, color = WmeColors.Muted)
        Slider(
            value = ui.packetsPerSecond.toFloat(),
            onValueChange = { viewModel.onRateChange(it.toInt()) },
            valueRange = AudioSyncSender.RATE_RANGE.first.toFloat()..AudioSyncSender.RATE_RANGE.last.toFloat(),
            enabled = enabled,
        )
    }
}

@Composable
private fun LampStatusText(lamp: LampStatus) {
    when (lamp) {
        LampStatus.Unknown -> Text(stringResource(R.string.lamp_unknown), color = WmeColors.Muted)
        LampStatus.Checking -> Text(stringResource(R.string.lamp_checking), color = WmeColors.Muted)
        is LampStatus.Available -> {
            Text(stringResource(R.string.lamp_available, lamp.info.name, lamp.info.version), color = WmeColors.Ok)
            when {
                !lamp.info.hasAudioReactive -> Hint(stringResource(R.string.lamp_ar_missing), WmeColors.Warn)
                lamp.info.audioSyncReceiveEnabled == true -> Hint(stringResource(R.string.lamp_ar_receive), WmeColors.Muted)
                else -> Hint(stringResource(R.string.lamp_ar_not_receive), WmeColors.Warn)
            }
        }
        is LampStatus.Unavailable -> Text(
            stringResource(
                when (lamp.reason) {
                    WledInfoResult.Reason.TIMEOUT -> R.string.lamp_unavailable_timeout
                    WledInfoResult.Reason.NETWORK -> R.string.lamp_unavailable_network
                    WledInfoResult.Reason.INVALID_RESPONSE -> R.string.lamp_unavailable_invalid
                }
            ),
            color = WmeColors.Error,
        )
    }
}

@Composable
private fun TransportCard(session: SessionState) {
    Card(stringResource(R.string.section_transport)) {
        val (text, color) = when (val t = session.transport) {
            TransportStatus.Idle -> stringResource(R.string.transport_idle) to WmeColors.Muted
            TransportStatus.Sending -> stringResource(R.string.transport_sending_to, session.target ?: "") to WmeColors.Ok
            TransportStatus.NoNetwork -> stringResource(R.string.transport_no_network) to WmeColors.Error
            is TransportStatus.Error -> "${stringResource(R.string.transport_error)}: ${t.message}" to WmeColors.Error
        }
        Text(text, color = color)
        Text(stringResource(R.string.packets_sent, session.packetsSent), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = WmeColors.Muted)
        if (session.avgDspMs > 0f) {
            Text(stringResource(R.string.dsp_time, session.avgDspMs), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = WmeColors.Muted)
        }
    }
}

@Composable
private fun StartStopButton(running: Boolean, enabled: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Button(
        onClick = if (running) onStop else onStart,
        enabled = enabled,
        colors = if (running) ButtonDefaults.buttonColors(containerColor = WmeColors.Error) else ButtonDefaults.buttonColors(),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .height(56.dp),
    ) {
        Text(stringResource(if (running) R.string.action_stop else R.string.action_start), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Hint(text: String, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

private fun captureColor(status: CaptureStatus): Color = when (status) {
    CaptureStatus.ACTIVE -> WmeColors.Ok
    CaptureStatus.SILENCE -> WmeColors.Warn
    CaptureStatus.INACTIVE -> WmeColors.Muted
    CaptureStatus.NO_PERMISSION, CaptureStatus.STOPPED_BY_SYSTEM -> WmeColors.Error
}

private fun lerpColor(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
)
