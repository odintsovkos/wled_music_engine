package com.wledmusic.engine.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wledmusic.engine.R
import com.wledmusic.engine.core.network.WledInfoResult
import com.wledmusic.engine.service.SyncNotification
import com.wledmusic.engine.session.CaptureStatus
import com.wledmusic.engine.session.LampStatus
import com.wledmusic.engine.session.RealtimeReceive
import com.wledmusic.engine.session.RestoreStatus
import com.wledmusic.engine.session.SessionState
import com.wledmusic.engine.session.SyncMode
import com.wledmusic.engine.settings.EngineSettings
import com.wledmusic.engine.ui.theme.StatusTextStyle
import com.wledmusic.engine.ui.theme.WmeColors

/**
 * Главный экран под сценарий «открыл → звук и лампа доступны → Start → убрал телефон».
 * Только статусы, компактный визуализатор, кнопка старта, карточка лампы и быстрый выбор эффекта.
 */
@Composable
fun SyncScreen(
    viewModel: MainViewModel,
    notificationsDenied: Boolean,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenProAudio: () -> Unit,
) {
    val ui = viewModel.ui.collectAsStateValue()
    val session = viewModel.session.collectAsStateValue()
    val engine = viewModel.engine.collectAsStateValue()
    val layout = viewModel.layout.collectAsStateValue()
    val features = viewModel.features.collectAsStateWithLifecycle()
    val canStart = viewModel.canStart(ui, engine, session.lamp, layout)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenSettings) { Text("⚙", fontSize = 22.sp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(captureColor(session.capture), size = 12)
            Text(stringResource(SyncNotification.captureLabel(session.capture)), style = StatusTextStyle)
        }
        Text(
            stringResource(if (ui.wifiConnected) R.string.wifi_connected else R.string.wifi_missing),
            color = if (ui.wifiConnected) WmeColors.Muted else WmeColors.Error,
            style = MaterialTheme.typography.bodyLarge,
        )
        Messages(viewModel, session, notificationsDenied)

        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp).clickable(onClick = onOpenProAudio),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SpectrumBars(features, Modifier.height(72.dp))
            BandMeters(features)
        }

        StartStopButton(
            running = session.isRunning,
            enabled = session.isRunning || canStart,
            onStart = viewModel::requestStart,
            onStop = onStop,
        )
        StartBlocker(ui, engine, session, layout != null)

        SectionTitle(stringResource(R.string.section_devices))
        LampSummaryCard(session, engine, onOpenDevices)

        if (engine.mode == SyncMode.RGB_ENGINE) {
            SectionTitle(stringResource(R.string.section_effect))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (effect in engine.recentEffects) {
                    FilterChip(selected = effect == engine.effect, onClick = { viewModel.selectEffect(effect) }, label = { Text(effect.displayName) })
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Messages(viewModel: MainViewModel, session: SessionState, notificationsDenied: Boolean) {
    if (session.captureBlockedSuspected) Hint(stringResource(R.string.hint_capture_blocked), WmeColors.Warn)
    if (session.capture == CaptureStatus.STOPPED_BY_SYSTEM) {
        Hint(stringResource(R.string.hint_stopped_by_system), WmeColors.Error)
        session.stopReason?.let { Hint(stringResource(R.string.stop_reason, it)) }
    }
    if (session.isRunning && session.mode == SyncMode.RGB_ENGINE) {
        when (session.realtime) {
            RealtimeReceive.RECEIVING -> Hint(stringResource(R.string.realtime_receiving), WmeColors.Ok)
            RealtimeReceive.NOT_RECEIVING -> Hint(stringResource(R.string.realtime_not_receiving), WmeColors.Warn)
            RealtimeReceive.OTHER_SOURCE -> Hint(stringResource(R.string.realtime_other_source), WmeColors.Warn)
            RealtimeReceive.UNKNOWN -> Unit
        }
    }
    when (session.restore) {
        RestoreStatus.RESTORED -> Row(verticalAlignment = Alignment.CenterVertically) {
            Hint(stringResource(R.string.restore_done), WmeColors.Ok)
            TextButton(onClick = viewModel::dismissRestoreMessage) { Text("✕") }
        }
        RestoreStatus.DEFERRED -> Hint(stringResource(R.string.restore_deferred), WmeColors.Warn)
        RestoreStatus.NONE -> Unit
    }
    if (notificationsDenied) Hint(stringResource(R.string.hint_notifications_denied))
}

/** Почему кнопка старта неактивна — одной строкой, без перехода на другие экраны. */
@Composable
private fun StartBlocker(ui: MainUiState, engine: EngineSettings, session: SessionState, hasLayout: Boolean) {
    if (session.isRunning) return
    val text = when {
        ui.preparing -> stringResource(R.string.start_preparing)
        !ui.hostValid -> stringResource(R.string.start_no_lamp)
        session.lamp is LampStatus.Unavailable -> stringResource(R.string.start_lamp_offline)
        ui.startError == StartError.LAMP_UNAVAILABLE -> stringResource(R.string.start_lamp_offline)
        engine.mode == SyncMode.RGB_ENGINE && session.lamp !is LampStatus.Available -> stringResource(R.string.start_check_lamp)
        engine.mode == SyncMode.RGB_ENGINE && !hasLayout -> stringResource(R.string.start_layout_unknown)
        ui.startError == StartError.LAYOUT_UNKNOWN -> stringResource(R.string.start_layout_unknown)
        else -> return
    }
    Hint(text, WmeColors.Muted)
}

@Composable
fun LampSummaryCard(session: SessionState, engine: EngineSettings, onClick: () -> Unit) {
    val lamp = session.lamp
    WmeCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (lamp as? LampStatus.Available)?.info?.name ?: stringResource(R.string.lamp_default_name),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            StatusDot(lampColor(lamp))
        }
        Text(lampStatusText(lamp), color = lampColor(lamp), style = MaterialTheme.typography.bodyMedium)
        if (lamp is LampStatus.Available) {
            val rgb = engine.mode == SyncMode.RGB_ENGINE
            val effectName = if (rgb) engine.effect.displayName else lamp.currentEffectName ?: "—"
            val brightness = if (rgb) engine.paramsFor(engine.effect).brightness
            else lamp.state?.brightness?.let { it * 100 / 255 }
            InfoRow(stringResource(R.string.label_effect), effectName)
            InfoRow(stringResource(R.string.label_brightness), brightness?.let { "$it%" } ?: "—")
        }
        InfoRow(stringResource(R.string.label_mode), modeLabel(engine.mode))
    }
}

@Composable
private fun StartStopButton(running: Boolean, enabled: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Button(
        onClick = if (running) onStop else onStart,
        enabled = enabled,
        colors = if (running) ButtonDefaults.buttonColors(containerColor = WmeColors.SurfaceHigh, contentColor = WmeColors.OnBackground)
        else ButtonDefaults.buttonColors(),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().height(64.dp),
    ) {
        Text(stringResource(if (running) R.string.action_stop_sync else R.string.action_start_sync), style = StatusTextStyle)
    }
}

@Composable
fun modeLabel(mode: SyncMode): String = stringResource(
    when (mode) {
        SyncMode.AUDIO_REACTIVE -> R.string.mode_audio_reactive
        SyncMode.RGB_ENGINE -> R.string.mode_rgb_engine
    }
)

@Composable
fun lampStatusText(lamp: LampStatus): String = when (lamp) {
    LampStatus.Unknown -> stringResource(R.string.lamp_unknown)
    LampStatus.Checking -> stringResource(R.string.lamp_checking)
    is LampStatus.Available -> stringResource(R.string.lamp_online, lamp.info.version)
    is LampStatus.Unavailable -> stringResource(
        when (lamp.reason) {
            WledInfoResult.Reason.TIMEOUT -> R.string.lamp_unavailable_timeout
            WledInfoResult.Reason.NETWORK -> R.string.lamp_unavailable_network
            WledInfoResult.Reason.INVALID_RESPONSE -> R.string.lamp_unavailable_invalid
        }
    )
}

fun lampColor(lamp: LampStatus): Color = when (lamp) {
    is LampStatus.Available -> WmeColors.Ok
    is LampStatus.Unavailable -> WmeColors.Error
    else -> WmeColors.Muted
}

fun captureColor(status: CaptureStatus): Color = when (status) {
    CaptureStatus.ACTIVE -> WmeColors.Ok
    CaptureStatus.SILENCE -> WmeColors.Warn
    CaptureStatus.INACTIVE -> WmeColors.Muted
    CaptureStatus.NO_PERMISSION, CaptureStatus.STOPPED_BY_SYSTEM -> WmeColors.Error
}
