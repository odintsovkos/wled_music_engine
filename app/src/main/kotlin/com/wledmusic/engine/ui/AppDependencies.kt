package com.wledmusic.engine.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.wledmusic.engine.WmeApplication
import com.wledmusic.engine.core.network.DdpCodec
import com.wledmusic.engine.core.network.DdpSender
import com.wledmusic.engine.core.network.LampStateGuard
import com.wledmusic.engine.core.network.WledAddress
import com.wledmusic.engine.core.network.WledInfoResult
import com.wledmusic.engine.core.render.AnimationDirection
import com.wledmusic.engine.core.render.Canvas
import com.wledmusic.engine.core.render.LedLayout
import com.wledmusic.engine.core.render.TestPattern
import com.wledmusic.engine.service.WifiBoundTransport
import com.wledmusic.engine.session.LampStatus
import com.wledmusic.engine.settings.EngineSettings
import com.wledmusic.engine.settings.LampSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.net.InetAddress

class AppDependencies(context: Context) : MainDependencies {
    private val app = context.applicationContext as WmeApplication
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override val session = app.sessionRepository
    override val savedSettings = app.settingsRepository.lamp
    override val engineSettings = app.settingsRepository.engine
    override val layout = app.layout
    override val previewSource = app.previewSource
    override suspend fun saveSettings(settings: LampSettings) = app.settingsRepository.save(settings)
    override suspend fun updateEngine(transform: (EngineSettings) -> EngineSettings) = app.settingsRepository.updateEngine(transform)

    override val wifiConnected: Flow<Boolean> = callbackFlow {
        val networks = HashSet<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { networks += network; trySend(true) }
            override fun onLost(network: Network) { networks -= network; trySend(networks.isNotEmpty()) }
        }
        trySend(app.lampConnection.wifiNetwork() != null)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.registerNetworkCallback(request, callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    override suspend fun checkLamp(host: String): LampStatus = withContext(Dispatchers.IO) {
        val lamp = app.lampConnection
        when (val info = lamp.info(host)) {
            is WledInfoResult.Failure -> LampStatus.Unavailable(info.reason, info.message)
            is WledInfoResult.Success -> {
                // Лампа снова видна — самое время довести отложенное восстановление.
                app.restorePendingLamp()
                val previous = session.state.value.lamp as? LampStatus.Available
                val names = previous?.effectNames?.takeIf { it.isNotEmpty() && previous.info.name == info.info.name }
                    ?: lamp.effectNames(host)
                LampStatus.Available(info.info, lamp.state(host).getOrNull(), names)
            }
        }
    }

    override suspend fun isLocalAddress(host: String): Boolean = withContext(Dispatchers.IO) {
        when (val parsed = WledAddress.parse(host)) {
            null -> false
            is WledAddress.Parsed.Ipv4 -> WledAddress.isLocal(InetAddress.getByName(parsed.host))
            is WledAddress.Parsed.Hostname -> WledAddress.isLocalHostname(parsed.host) || runCatching {
                WledAddress.isLocal(app.lampConnection.wifiNetwork()?.getByName(parsed.host) ?: InetAddress.getByName(parsed.host))
            }.getOrDefault(true) // не резолвится — предупреждать не о чем, проверка лампы покажет ошибку
        }
    }

    override suspend fun prepareLamp(host: String, realtime: Boolean, allowOverrideChange: Boolean) =
        withContext(Dispatchers.IO) { app.lampGuard.prepare(host, realtime, allowOverrideChange) }

    override suspend fun cancelPrepared(host: String) {
        withContext(Dispatchers.IO) { app.lampGuard.finish(host, exitRealtime = false) }
    }

    override suspend fun showTestPattern(host: String, layout: LedLayout, direction: AnimationDirection, seconds: Int): Boolean =
        withContext(Dispatchers.IO) {
            val guard = app.lampGuard
            if (guard.prepare(host, realtime = true, allowOverrideChange = false) !is LampStateGuard.PrepareResult.Ready) {
                return@withContext false
            }
            val canvas = Canvas(layout)
            TestPattern.draw(canvas, direction)
            val transport = WifiBoundTransport(host, DdpCodec.PORT)
            transport.onNetworkChanged(app.lampConnection.wifiNetwork())
            var ok = true
            try {
                val sender = DdpSender(transport)
                // Кадр повторяется: WLED выходит из realtime, если поток прерывается дольше таймаута.
                repeat(seconds * TEST_PATTERN_FPS) {
                    ok = sender.send(canvas.rgb, layout.ledCount) && ok
                    delay(1_000L / TEST_PATTERN_FPS)
                }
            } finally {
                transport.close()
                guard.finish(host, exitRealtime = true)
            }
            ok
        }

    private companion object {
        const val TEST_PATTERN_FPS = 10
    }
}
