package com.wledmusic.engine.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.wledmusic.engine.service.SyncService
import com.wledmusic.engine.ui.theme.WmeTheme
import kotlinx.coroutines.launch

/**
 * Поток запуска: «Старт» → (пояснение и runtime-разрешения) → системное согласие MediaProjection →
 * Foreground Service. Каждая сессия требует нового согласия.
 */
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(AppDependencies(this@MainActivity)) as T
        }
    }

    private var pendingStart: StartRequest? = null
    private var showPermissionRationale by mutableStateOf(false)
    private var notificationsDenied by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        notificationsDenied = result[Manifest.permission.POST_NOTIFICATIONS] == false
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            requestProjection()
        } else {
            pendingStart = null
            viewModel.onPermissionDenied()
        }
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = pendingStart
        pendingStart = null
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null && request != null) {
            startForegroundService(
                SyncService.startIntent(this, result.resultCode, data, request.host, request.port, request.packetsPerSecond)
            )
        } else {
            viewModel.onPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            viewModel.startRequests.collect { request ->
                pendingStart = request
                if (hasPermission(Manifest.permission.RECORD_AUDIO)) requestProjection() else showPermissionRationale = true
            }
        }
        setContent {
            WmeTheme {
                MainScreen(
                    viewModel = viewModel,
                    showPermissionRationale = showPermissionRationale,
                    notificationsDenied = notificationsDenied,
                    onPermissionRationaleContinue = {
                        showPermissionRationale = false
                        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                    },
                    onPermissionRationaleDismiss = {
                        showPermissionRationale = false
                        pendingStart = null
                    },
                    onStop = { startService(SyncService.stopIntent(this)) },
                )
            }
        }
    }

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        // Захват всего экрана: при захвате одного приложения звук других приложений недоступен.
        projectionLauncher.launch(manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()))
    }

    private fun hasPermission(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
