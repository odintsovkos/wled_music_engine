package com.wledmusic.engine.service

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.util.Log
import com.wledmusic.engine.WmeApplication
import com.wledmusic.engine.core.audio.CaptureSession
import com.wledmusic.engine.core.dsp.DspEngine
import com.wledmusic.engine.core.network.AudioSyncSender
import com.wledmusic.engine.core.network.SenderStatus
import com.wledmusic.engine.session.SessionStateRepository
import com.wledmusic.engine.session.TransportStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground Service сессии синхронизации: MediaProjection → захват → DSP → UDP.
 * Не перезапускается системой (START_NOT_STICKY): новая сессия требует нового согласия пользователя.
 */
class SyncService : Service() {
    private val repository: SessionStateRepository get() = (application as WmeApplication).sessionRepository
    private var scope: CoroutineScope? = null
    private var session: CaptureSession? = null
    private var pipeline: AudioPipeline? = null
    private var transport: WifiBoundTransport? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stopByUser()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (session != null) return
        SyncNotification.ensureChannel(this)
        // Android 14+: FGS типа mediaProjection должен быть запущен до getMediaProjection().
        startForeground(
            SyncNotification.ONGOING_ID,
            SyncNotification.ongoing(this, repository.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        val rate = intent.getIntExtra(EXTRA_RATE, AudioSyncSender.DEFAULT_RATE)
        if (resultCode != Activity.RESULT_OK || data == null || host == null ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            repository.onPermissionDenied()
            shutdown()
            return
        }

        val projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
        if (projection == null) {
            repository.onPermissionDenied()
            shutdown()
            return
        }

        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        val engine = DspEngine()
        val captureSession = CaptureSession(
            projection = projection,
            onStoppedBySystem = { reason -> stopBySystem(reason) },
            sampleRate = engine.config.sampleRate,
            blockFrames = engine.config.hopSize,
        )
        try {
            captureSession.start()
        } catch (e: Exception) {
            Log.e(TAG, "capture start failed", e)
            repository.onSessionStarted("$host:$port")
            stopBySystem(e.message ?: "capture start failed")
            return
        }
        session = captureSession
        repository.onSessionStarted("$host:$port")

        val audioManager = getSystemService(AudioManager::class.java)
        pipeline = AudioPipeline(captureSession.capture.buffer, engine, repository) { audioManager.isMusicActive }
            .also { it.start() }

        val wifi = WifiBoundTransport(host, port).also { transport = it }
        val wifiAvailable = MutableStateFlow(false)
        registerWifiCallback(wifi, wifiAvailable)

        val sender = AudioSyncSender(wifi, rate.coerceIn(AudioSyncSender.RATE_RANGE))
        serviceScope.launch(Dispatchers.IO) { sender.run(repository.features) }
        serviceScope.launch {
            combine(sender.status, sender.packetsSent, wifiAvailable) { status, sent, hasWifi ->
                val transportStatus = when {
                    !hasWifi -> TransportStatus.NoNetwork
                    status is SenderStatus.Error -> TransportStatus.Error(status.message)
                    status == SenderStatus.Sending -> TransportStatus.Sending
                    else -> TransportStatus.Idle
                }
                transportStatus to sent
            }.collect { (status, sent) -> repository.onTransport(status, sent) }
        }
        serviceScope.launch {
            val notifications = getSystemService(NotificationManager::class.java)
            repository.state
                .map { SyncNotification.statusText(this@SyncService, it) to it.isRunning }
                .distinctUntilChanged()
                .collect { (_, running) ->
                    if (running) {
                        notifications.notify(SyncNotification.ONGOING_ID, SyncNotification.ongoing(this@SyncService, repository.state.value))
                    }
                    delay(NOTIFICATION_MIN_INTERVAL_MS)
                }
        }
    }

    private fun registerWifiCallback(wifi: WifiBoundTransport, available: MutableStateFlow<Boolean>) {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null
            override fun onAvailable(network: Network) {
                current = network
                wifi.onNetworkChanged(network)
                available.value = true
            }
            override fun onLost(network: Network) {
                if (network != current) return
                current = null
                wifi.onNetworkChanged(null)
                available.value = false
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // Direct AP: сеть WLED без интернета тоже подходит.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.registerNetworkCallback(request, callback)
        networkCallback = callback
    }

    private fun stopByUser() {
        val wasActive = session != null
        repository.onStoppedByUser()
        if (!wasActive) {
            stopSelf()
            return
        }
        shutdown()
    }

    private fun stopBySystem(reason: String) {
        Log.w(TAG, "stopped by system: $reason")
        repository.onStoppedBySystem(reason)
        releasePipeline()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)
            .notify(SyncNotification.STOPPED_ID, SyncNotification.stoppedBySystem(this))
        stopSelf()
    }

    private fun shutdown() {
        releasePipeline()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releasePipeline() {
        scope?.cancel()
        scope = null
        pipeline?.stop()
        pipeline = null
        session?.stop()
        session = null
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        networkCallback = null
        transport?.close()
        transport = null
    }

    override fun onDestroy() {
        if (session != null) {
            // Служба уничтожена без штатной остановки — честно сообщаем об этом.
            repository.onStoppedBySystem("service destroyed")
            releasePipeline()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SyncService"
        private const val ACTION_START = "com.wledmusic.engine.action.START"
        private const val ACTION_STOP = "com.wledmusic.engine.action.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_RATE = "rate"
        private const val NOTIFICATION_MIN_INTERVAL_MS = 1_000L

        fun startIntent(context: Context, resultCode: Int, data: Intent, host: String, port: Int, rate: Int): Intent =
            Intent(context, SyncService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_RATE, rate)

        fun stopIntent(context: Context): Intent = Intent(context, SyncService::class.java).setAction(ACTION_STOP)
    }
}
