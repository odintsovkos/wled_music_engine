package com.wledmusic.engine.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/**
 * Отправка кадров пикселей в WLED по DDP. Вызывается из рендер-цикла на каждый новый кадр;
 * буферы пакетов выделяются один раз. Ошибка сети помечает статус и не бросается дальше:
 * следующий кадр будет отправлен как обычно, старые кадры не копятся.
 */
class DdpSender(private val transport: DatagramTransport, maxLeds: Int = MAX_LEDS) {
    private val packets = Array(DdpCodec.packetCount(maxLeds)) { ByteArray(DdpCodec.HEADER_SIZE + DdpCodec.MAX_DATA_SIZE) }
    private var sequence = 0

    private val _status = MutableStateFlow<SenderStatus>(SenderStatus.Idle)
    val status: StateFlow<SenderStatus> = _status.asStateFlow()

    private val _framesSent = MutableStateFlow(0L)
    val framesSent: StateFlow<Long> = _framesSent.asStateFlow()

    /** Отправляет кадр из [ledCount] LED; `true`, если все пакеты ушли. */
    fun send(rgb: ByteArray, ledCount: Int): Boolean {
        val count = DdpCodec.packetCount(ledCount)
        require(count <= packets.size) { "frame of $ledCount LEDs exceeds sender capacity" }
        sequence = sequence % 15 + 1
        return try {
            for (i in 0 until count) {
                val size = DdpCodec.encodePacket(rgb, ledCount, i, sequence, packets[i])
                transport.send(packets[i], size)
            }
            _framesSent.value++
            _status.value = SenderStatus.Sending
            true
        } catch (e: IOException) {
            _status.value = SenderStatus.Error(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    fun markIdle() {
        _status.value = SenderStatus.Idle
    }

    companion object {
        const val MAX_LEDS = 1024
    }
}
