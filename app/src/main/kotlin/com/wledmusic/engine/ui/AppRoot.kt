package com.wledmusic.engine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.wledmusic.engine.R

/** Вкладки нижней навигации. */
enum class Tab(val glyph: String, val label: Int) {
    SYNC("♫", R.string.tab_sync),
    DEVICES("◉", R.string.tab_devices),
    EFFECTS("✦", R.string.tab_effects),
    SETTINGS("⚙", R.string.tab_settings),
}

/** Вложенные экраны без собственной вкладки. */
enum class Sub { NONE, PRO_AUDIO, ORIENTATION }

/**
 * Корень UI: четыре вкладки внизу, Pro Audio и ориентация матрицы — вложенные экраны
 * («Назад» возвращает на исходную вкладку). Диалоги старта общие для всех вкладок.
 */
@Composable
fun AppRoot(
    viewModel: MainViewModel,
    showPermissionRationale: Boolean,
    notificationsDenied: Boolean,
    onPermissionRationaleContinue: () -> Unit,
    onPermissionRationaleDismiss: () -> Unit,
    onStop: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.SYNC) }
    var sub by rememberSaveable { mutableStateOf(Sub.NONE) }
    BackHandler(enabled = sub != Sub.NONE || tab != Tab.SYNC) {
        if (sub != Sub.NONE) sub = Sub.NONE else tab = Tab.SYNC
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                for (t in Tab.values()) {
                    NavigationBarItem(
                        selected = t == tab && sub == Sub.NONE,
                        onClick = { tab = t; sub = Sub.NONE },
                        icon = { Text(t.glyph, fontSize = 20.sp) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (sub) {
                Sub.PRO_AUDIO -> ProAudioScreen(viewModel, onBack = { sub = Sub.NONE })
                Sub.ORIENTATION -> OrientationScreen(viewModel, onBack = { sub = Sub.NONE })
                Sub.NONE -> when (tab) {
                    Tab.SYNC -> SyncScreen(
                        viewModel,
                        notificationsDenied = notificationsDenied,
                        onStop = onStop,
                        onOpenSettings = { tab = Tab.SETTINGS },
                        onOpenDevices = { tab = Tab.DEVICES },
                        onOpenProAudio = { sub = Sub.PRO_AUDIO },
                    )
                    Tab.DEVICES -> DevicesScreen(viewModel, onOpenOrientation = { sub = Sub.ORIENTATION })
                    Tab.EFFECTS -> EffectsScreen(viewModel, onOpenDevices = { tab = Tab.DEVICES })
                    Tab.SETTINGS -> SettingsScreen(viewModel, onOpenProAudio = { sub = Sub.PRO_AUDIO })
                }
            }
        }
    }

    StartDialogs(viewModel, showPermissionRationale, onPermissionRationaleContinue, onPermissionRationaleDismiss)
}

@Composable
private fun StartDialogs(
    viewModel: MainViewModel,
    showPermissionRationale: Boolean,
    onPermissionRationaleContinue: () -> Unit,
    onPermissionRationaleDismiss: () -> Unit,
) {
    val ui = viewModel.ui.collectAsStateValue()
    if (ui.confirmPublicAddress) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPublicAddress,
            title = { Text(stringResource(R.string.public_title)) },
            text = { Text(stringResource(R.string.public_text, ui.host)) },
            confirmButton = { TextButton(onClick = viewModel::confirmPublicAddress) { Text(stringResource(R.string.action_send_anyway)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissPublicAddress) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    ui.overrideConsent?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissOverrideChange,
            title = { Text(stringResource(R.string.override_title)) },
            text = { Text(stringResource(R.string.override_text)) },
            confirmButton = { TextButton(onClick = viewModel::confirmOverrideChange) { Text(stringResource(R.string.action_override_off)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissOverrideChange) { Text(stringResource(R.string.action_cancel)) } },
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
