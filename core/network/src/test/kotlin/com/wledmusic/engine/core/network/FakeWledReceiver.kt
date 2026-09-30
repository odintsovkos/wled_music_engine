package com.wledmusic.engine.core.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Эмулятор WLED в режиме Audio Sync Receive: слушает UDP на loopback и
 * принимает пакеты по тем же правилам, что и прошивка (ровно 44 байта + заголовок "00002").
 */
class FakeWledReceiver : AutoCloseable {
    private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
    val port: Int get() = socket.localPort
    val address: InetAddress get() = InetAddress.getLoopbackAddress()

    val accepted = CopyOnWriteArrayList<AudioSyncPacket>()
    @Volatile var rejected = 0
        private set

    private val worker = thread(isDaemon = true, name = "fake-wled") {
        val buf = ByteArray(128)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: SocketException) {
                break
            }
            val decoded = AudioSyncCodec.decode(packet.data.copyOf(packet.length), packet.length)
            if (decoded != null) accepted += decoded else rejected++
        }
    }

    fun awaitPackets(count: Int, timeoutMs: Long = 2_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (accepted.size < count && System.currentTimeMillis() < deadline) Thread.sleep(5)
        return accepted.size >= count
    }

    override fun close() {
        socket.close()
        worker.join(1_000)
    }
}
