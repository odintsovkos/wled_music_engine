package com.wledmusic.engine.lamp

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.wledmusic.engine.core.network.LampApi
import com.wledmusic.engine.core.network.WledInfoResult
import com.wledmusic.engine.core.network.WledJsonClient
import com.wledmusic.engine.core.network.WledResult
import com.wledmusic.engine.core.network.WledState
import java.net.Inet4Address

/**
 * HTTP-доступ к лампе через Wi-Fi-сеть (в том числе без интернета — Direct AP), даже если она
 * не сеть по умолчанию. Все методы блокирующие: вызывать из IO-потока.
 */
class LampConnection(private val connectivity: ConnectivityManager) : LampApi {
    private fun client(): WledJsonClient {
        val network = wifiNetwork()
        return WledJsonClient(openConnection = { url -> network?.openConnection(url) ?: url.openConnection() })
    }

    /** Результат `/json/info` и время запроса в мс (для оценки сетевой задержки). */
    fun infoTimed(host: String): Pair<WledInfoResult, Float> {
        val client = client()
        val t0 = System.nanoTime()
        val result = client.info(host)
        return result to (System.nanoTime() - t0) / 1e6f
    }

    fun info(host: String): WledInfoResult = client().info(host)

    fun effectNames(host: String): List<String> = client().effectNames(host).getOrNull() ?: emptyList()

    override fun state(host: String): WledResult<WledState> = client().state(host)

    override fun postState(host: String, body: String): WledResult<Unit> = client().postState(host, body)

    /** IPv4-адрес телефона в Wi-Fi-сети — для сравнения с `info.lip`. */
    fun localWifiAddress(): String? {
        val network = wifiNetwork() ?: return null
        return connectivity.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress
    }

    @Suppress("DEPRECATION")
    fun wifiNetwork(): Network? = connectivity.allNetworks.firstOrNull {
        connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
}
