package com.wledmusic.engine.core.network

import com.wledmusic.engine.core.dsp.AudioFeatures
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import kotlin.math.ceil

/** Отправка готового пакета. Реализации могут блокироваться. */
interface DatagramTransport : AutoCloseable {
    @Throws(IOException::class)
    fun send(data: ByteArray)

    /** Отправляет первые [length] байт [data]. */
    @Throws(IOException::class)
    fun send(data: ByteArray, length: Int) = send(if (length == data.size) data else data.copyOf(length))
}

/**
 * UDP-транспорт на [address]:[port]. Для multicast выставляется TTL 1 (только локальная сеть).
 * [configureSocket] позволяет привязать сокет к конкретной сети (Android `Network.bindSocket`).
 */
class UdpTransport(
    private val address: InetAddress,
    private val port: Int,
    configureSocket: (DatagramSocket) -> Unit = {},
) : DatagramTransport {
    private val socket: DatagramSocket =
        if (address.isMulticastAddress) MulticastSocket().apply { timeToLive = 1 } else DatagramSocket()

    init {
        require(port in 1..65535) { "invalid port $port" }
        configureSocket(socket)
    }

    override fun send(data: ByteArray) = send(data, data.size)

    override fun send(data: ByteArray, length: Int) {
        socket.send(DatagramPacket(data, length, address, port))
    }

    override fun close() = socket.close()
}

sealed interface SenderStatus {
    data object Idle : SenderStatus
    data object Sending : SenderStatus
    data class Error(val message: String) : SenderStatus
}

/**
 * Отправляет последний доступный кадр признаков не чаще [packetsPerSecond] раз в секунду.
 * Устаревшие кадры не буферизуются: на каждом тике берётся только текущее значение [frames],
 * и уже отправленный кадр повторно не шлётся. Ошибки сети не прерывают цикл.
 */
class AudioSyncSender(
    private val transport: DatagramTransport,
    packetsPerSecond: Int = DEFAULT_RATE,
) {
    init {
        require(packetsPerSecond in RATE_RANGE) { "rate must be in $RATE_RANGE" }
    }

    private val intervalMs = ceil(1000.0 / packetsPerSecond).toLong()
    private val buffer = ByteArray(AudioSyncCodec.PACKET_SIZE)

    private val _status = MutableStateFlow<SenderStatus>(SenderStatus.Idle)
    val status: StateFlow<SenderStatus> = _status.asStateFlow()

    private val _packetsSent = MutableStateFlow(0L)
    val packetsSent: StateFlow<Long> = _packetsSent.asStateFlow()

    /** Цикл отправки; завершается при отмене корутины. Вызывать в IO-диспетчере. */
    suspend fun run(frames: StateFlow<AudioFeatures?>) {
        var lastSent: AudioFeatures? = null
        try {
            while (currentCoroutineContext().isActive) {
                val frame = frames.value
                if (frame != null && frame !== lastSent) {
                    lastSent = frame
                    try {
                        transport.send(AudioSyncCodec.encode(AudioSyncPacket.from(frame), buffer))
                        _packetsSent.value++
                        _status.value = SenderStatus.Sending
                    } catch (e: IOException) {
                        _status.value = SenderStatus.Error(e.message ?: e.javaClass.simpleName)
                    }
                }
                delay(intervalMs)
            }
        } finally {
            _status.value = SenderStatus.Idle
        }
    }

    companion object {
        const val DEFAULT_RATE = 50
        val RATE_RANGE = 10..60
    }
}
