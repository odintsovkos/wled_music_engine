package com.wledmusic.engine.service

import android.net.Network
import com.wledmusic.engine.core.network.DatagramTransport
import com.wledmusic.engine.core.network.UdpTransport
import java.io.IOException

/**
 * UDP-транспорт, привязанный к текущей Wi-Fi-сети. Привязка нужна, чтобы в режиме Direct AP
 * (Wi-Fi без интернета) Android не отправлял пакеты через мобильную сеть по умолчанию.
 * При смене или потере сети сокет пересоздаётся; без Wi-Fi [send] бросает [IOException].
 */
class WifiBoundTransport(
    private val host: String,
    private val port: Int,
) : DatagramTransport {
    private val lock = Any()
    private var network: Network? = null
    private var current: UdpTransport? = null

    fun onNetworkChanged(network: Network?) = synchronized(lock) {
        if (this.network == network) return
        current?.close()
        current = null
        this.network = network
    }

    override fun send(data: ByteArray) {
        val transport = synchronized(lock) {
            current ?: run {
                val n = network ?: throw IOException("No Wi-Fi network")
                // Имя хоста резолвится через ту же сеть; IP-литерал возвращается без DNS.
                val address = n.getByName(host)
                UdpTransport(address, port) { n.bindSocket(it) }.also { current = it }
            }
        }
        transport.send(data)
    }

    override fun close() = synchronized(lock) {
        current?.close()
        current = null
    }
}
