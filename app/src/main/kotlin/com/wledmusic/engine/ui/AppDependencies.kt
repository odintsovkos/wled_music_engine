package com.wledmusic.engine.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.wledmusic.engine.WmeApplication
import com.wledmusic.engine.core.network.WledAddress
import com.wledmusic.engine.core.network.WledInfoResult
import com.wledmusic.engine.core.network.WledJsonClient
import com.wledmusic.engine.settings.LampSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

class AppDependencies(context: Context) : MainDependencies {
    private val app = context.applicationContext as WmeApplication
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override val session = app.sessionRepository
    override val savedSettings = app.settingsRepository.lamp
    override suspend fun saveSettings(settings: LampSettings) = app.settingsRepository.save(settings)

    override suspend fun checkLamp(host: String): WledInfoResult = withContext(Dispatchers.IO) {
        val network = wifiNetwork()
        WledJsonClient(openConnection = { url -> network?.openConnection(url) ?: url.openConnection() }).info(host)
    }

    override suspend fun isLocalAddress(host: String): Boolean = withContext(Dispatchers.IO) {
        when (val parsed = WledAddress.parse(host)) {
            null -> false
            is WledAddress.Parsed.Ipv4 -> WledAddress.isLocal(InetAddress.getByName(parsed.host))
            is WledAddress.Parsed.Hostname -> WledAddress.isLocalHostname(parsed.host) || runCatching {
                WledAddress.isLocal(wifiNetwork()?.getByName(parsed.host) ?: InetAddress.getByName(parsed.host))
            }.getOrDefault(true) // не резолвится — предупреждать не о чем, проверка лампы покажет ошибку
        }
    }

    /** Wi-Fi-сеть, в том числе без интернета (Direct AP), даже если она не сеть по умолчанию. */
    @Suppress("DEPRECATION")
    private fun wifiNetwork(): Network? = connectivity.allNetworks.firstOrNull {
        connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
}
