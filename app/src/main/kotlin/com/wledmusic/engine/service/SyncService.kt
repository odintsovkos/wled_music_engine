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
import android.os.SystemClock
import android.util.Log
import com.wledmusic.engine.WmeApplication
import com.wledmusic.engine.core.audio.CaptureSession
import com.wledmusic.engine.core.dsp.DspEngine
import com.wledmusic.engine.core.network.AudioSyncSender
import com.wledmusic.engine.core.network.DdpCodec
import com.wledmusic.engine.core.network.DdpSender
import com.wledmusic.engine.core.network.LampStateGuard
import com.wledmusic.engine.core.network.SenderStatus
import com.wledmusic.engine.core.network.WledInfoResult
import com.wledmusic.engine.core.render.RenderLoop
import com.wledmusic.engine.session.RealtimeReceive
import com.wledmusic.engine.session.RestoreStatus
import com.wledmusic.engine.session.SessionStateRepository
import com.wledmusic.engine.session.SyncMode
import com.wledmusic.engine.session.TransportStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground Service сессии синхронизации: MediaProjection → захват → DSP → UDP.
 * Режим Audio Reactive шлёт признаки (Audio Sync v2), режим RGB Engine рисует кадры на телефоне
 * и шлёт их по DDP. При любом завершении состояние лампы восстанавливается ([LampStateGuard]).
 * Не перезапускается системой (START_NOT_STICKY): новая сессия требует нового согласия пользователя.
 */
class SyncService : Service() {
    private val app: WmeApplication get() = application as WmeApplication
    private val repository: SessionStateRepository get() = app.sessionRepository
    private var scope: CoroutineScope? = null
    private var session: CaptureSession? = null
    private var pipeline: AudioPipeline? = null
    private var transport: WifiBoundTransport? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var activeHost: String? = null
    private var activeMode: SyncMode = SyncMode.AUDIO_REACTIVE

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
        val mode = intent.getStringExtra(EXTRA_MODE)?.let { m -> SyncMode.values().firstOrNull { it.name == m } } ?: SyncMode.AUDIO_REACTIVE
        val layout = app.layout.value
        if (resultCode != Activity.RESULT_OK || data == null || host == null ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            repository.onPermissionDenied()
            // Поток не начинался: выходить из realtime не нужно, откатываем только журнал.
            finishLamp(host, exitRealtime = false)
            shutdown()
            return
        }

        val projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
        if (projection == null) {
            repository.onPermissionDenied()
            // Поток не начинался: выходить из realtime не нужно, откатываем только журнал.
            finishLamp(host, exitRealtime = false)
            shutdown()
            return
        }
        if (mode == SyncMode.RGB_ENGINE && layout == null) {
            // UI не даёт стартовать без раскладки; сюда можно попасть, только если лампа пропала из статуса.
            projection.stop()
            repository.onSessionStarted(host, mode)
            finishLamp(host, exitRealtime = false)
            stopBySystem("LED layout is unknown")
            return
        }
        activeHost = host
        activeMode = mode
        val targetPort = if (mode == SyncMode.RGB_ENGINE) DdpCodec.PORT else port
        val target = "$host:$targetPort"

        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        val engine = DspEngine(app.dspConfig.value)
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
            repository.onSessionStarted(target, mode)
            stopBySystem(e.message ?: "capture start failed")
            return
        }
        session = captureSession
        repository.onSessionStarted(target, mode)

        val audioManager = getSystemService(AudioManager::class.java)
        pipeline = AudioPipeline(
            captureSession.capture.buffer, engine, repository,
            captureTimeNanos = captureSession.capture::captureTimeNanos,
        ) { audioManager.isMusicActive }.also { it.start() }
        // Pro Audio: параметры DSP применяются между кадрами, без перезапуска захвата.
        serviceScope.launch {
            app.dspConfig.collect { config -> if (config != engine.config) runCatching { engine.reconfigure(config) } }
        }

        val wifi = WifiBoundTransport(host, targetPort).also { transport = it }
        val wifiAvailable = MutableStateFlow(false)
        registerWifiCallback(wifi, wifiAvailable)

        val status: StateFlow<SenderStatus>
        val sent: StateFlow<Long>
        if (mode == SyncMode.RGB_ENGINE) {
            val ddp = DdpSender(wifi)
            val loop = RenderLoop(layout!!, app.renderSettings, { rgb, count, features ->
                if (ddp.send(rgb, count)) repository.onFrameSent(features, System.nanoTime())
            }, fps = app.engineSettings.value.fps)
            serviceScope.launch(Dispatchers.Default) { loop.run(repository.features) }
            serviceScope.launch { loop.avgRenderMs.collect(repository::onRenderTiming) }
            app.previewSource.value = loop.preview
            status = ddp.status
            sent = ddp.framesSent
        } else {
            val sender = AudioSyncSender(wifi, rate.coerceIn(AudioSyncSender.RATE_RANGE)) { frame ->
                repository.onFrameSent(frame, System.nanoTime())
            }
            serviceScope.launch(Dispatchers.IO) { sender.run(repository.features) }
            status = sender.status
            sent = sender.packetsSent
        }
        serviceScope.launch {
            combine(status, sent, wifiAvailable) { s, count, hasWifi ->
                val transportStatus = when {
                    !hasWifi -> TransportStatus.NoNetwork
                    s is SenderStatus.Error -> TransportStatus.Error(s.message)
                    s == SenderStatus.Sending -> TransportStatus.Sending
                    else -> TransportStatus.Idle
                }
                transportStatus to count
            }.collect { (s, count) -> repository.onTransport(s, count) }
        }
        serviceScope.launch { pollLamp(host, mode) }
        serviceScope.launch {
            while (isActive) {
                delay(1_000)
                val snapshot = repository.latency.snapshot()
                repository.onLatency(snapshot, repository.latency.warning(snapshot, SystemClock.elapsedRealtime()))
            }
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

    /**
     * Опрос `/json/info` раз в [POLL_INTERVAL_MS]: RTT для оценки сетевой задержки и,
     * в RGB Engine, контроль приёма потока (`live` и `lip`).
     */
    private suspend fun pollLamp(host: String, mode: SyncMode) {
        val started = SystemClock.elapsedRealtime()
        var interval = REALTIME_GRACE_MS + 500
        while (true) {
            delay(interval)
            interval = POLL_INTERVAL_MS
            val (result, rttMs) = withContext(Dispatchers.IO) { app.lampConnection.infoTimed(host) }
            if (result !is WledInfoResult.Success) continue
            repository.latency.onRtt(rttMs)
            if (mode != SyncMode.RGB_ENGINE) continue
            val info = result.info
            val phone = withContext(Dispatchers.IO) { app.lampConnection.localWifiAddress() }
            val receive = when {
                info.live && (info.liveIp == null || phone == null || info.liveIp == phone) -> RealtimeReceive.RECEIVING
                info.live -> RealtimeReceive.OTHER_SOURCE
                SystemClock.elapsedRealtime() - started >= REALTIME_GRACE_MS -> RealtimeReceive.NOT_RECEIVING
                else -> RealtimeReceive.UNKNOWN
            }
            repository.onRealtime(receive)
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
        app.previewSource.value = null
        finishLamp(activeHost, exitRealtime = activeMode == SyncMode.RGB_ENGINE)
        activeHost = null
    }

    /**
     * Восстановление лампы после сессии — в области приложения, чтобы довести его после
     * остановки службы. Поток пикселей к этому моменту уже остановлен.
     */
    private fun finishLamp(host: String?, exitRealtime: Boolean) {
        host ?: return
        val app = app
        app.appScope.launch(Dispatchers.IO) {
            val result = app.lampGuard.finish(host, exitRealtime)
            app.sessionRepository.onRestore(
                when (result) {
                    is LampStateGuard.RestoreResult.Restored -> RestoreStatus.RESTORED
                    is LampStateGuard.RestoreResult.Deferred -> RestoreStatus.DEFERRED
                    LampStateGuard.RestoreResult.NothingToRestore -> RestoreStatus.NONE
                }
            )
        }
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
        private const val EXTRA_MODE = "mode"
        private const val NOTIFICATION_MIN_INTERVAL_MS = 1_000L
        private const val POLL_INTERVAL_MS = 5_000L
        /** Столько ждём `info.live` после старта, прежде чем сказать «лампа не принимает». */
        private const val REALTIME_GRACE_MS = 3_000L

        fun startIntent(
            context: Context, resultCode: Int, data: Intent, host: String, port: Int, rate: Int, mode: SyncMode,
        ): Intent =
            Intent(context, SyncService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_RATE, rate)
                .putExtra(EXTRA_MODE, mode.name)

        fun stopIntent(context: Context): Intent = Intent(context, SyncService::class.java).setAction(ACTION_STOP)
    }
}
