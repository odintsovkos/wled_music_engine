package com.wledmusic.engine.core.network

import com.wledmusic.engine.core.dsp.AudioFeatures
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Пакет WLED Audio Sync v2 (usermod AudioReactive, режим Receive).
 *
 * Раскладка сверена с `usermods/audioreactive/audio_reactive.cpp`, struct `audioSyncPacket`,
 * WLED main @ 8db3d7a82f307258c2a874a9c97e643adbcb3b64 (2026-07-26). 44 байта, little-endian (ESP32):
 *
 * | offset | поле            | тип        |
 * |-------:|-----------------|------------|
 * | 0      | header "00002"  | char[6]    |
 * | 6      | reserved1       | uint8[2]   |
 * | 8      | sampleRaw       | float      |
 * | 12     | sampleSmth      | float      |
 * | 16     | samplePeak      | uint8      |
 * | 17     | reserved2       | uint8      |
 * | 18     | fftResult[16]   | uint8[16]  |
 * | 34     | reserved3       | uint16     |
 * | 36     | FFT_Magnitude   | float      |
 * | 40     | FFT_MajorPeak   | float      |
 *
 * WLED принимает пакет, только если его длина ровно 44 байта и заголовок совпадает.
 */
class AudioSyncPacket(
    /** Громкость без сглаживания, шкала WLED 0..255. */
    val sampleRaw: Float,
    /** Сглаженная громкость, шкала WLED 0..255. */
    val sampleSmth: Float,
    val samplePeak: Boolean,
    /** 16 GEQ-каналов, 0..254. */
    val fftResult: IntArray,
    /** Амплитуда доминирующей частоты; эффекты WLED делят её на 4..16. */
    val fftMagnitude: Float,
    /** Доминирующая частота, Гц; WLED ограничивает 1..11025. */
    val fftMajorPeak: Float,
) {
    init {
        require(fftResult.size == AudioSyncCodec.CHANNELS)
    }

    companion object {
        /**
         * Маппинг признаков DSP на шкалы, которые ожидают эффекты WLED.
         * FFT_Magnitude ограничен 1020, чтобы эффекты, делящие его на 4 и приводящие к uint8, не переполнялись.
         */
        fun from(features: AudioFeatures): AudioSyncPacket {
            require(features.bands.size == AudioSyncCodec.CHANNELS) { "expected 16 bands" }
            return AudioSyncPacket(
                sampleRaw = features.rawLevel.coerceIn(0f, 1f) * 255f,
                sampleSmth = features.level.coerceIn(0f, 1f) * 255f,
                samplePeak = features.peak,
                fftResult = IntArray(AudioSyncCodec.CHANNELS) {
                    (features.bands[it].coerceIn(0f, 1f) * 254f + 0.5f).toInt()
                },
                fftMagnitude = features.majorPeakLevel.coerceIn(0f, 1f) * 1020f,
                fftMajorPeak = if (features.gateOpen) features.majorPeakHz.coerceIn(1f, 11025f) else 1f,
            )
        }
    }
}

object AudioSyncCodec {
    const val PACKET_SIZE = 44
    const val CHANNELS = 16
    const val DEFAULT_PORT = 11988
    const val MULTICAST_ADDRESS = "239.0.0.1"
    private val HEADER = byteArrayOf('0'.code.toByte(), '0'.code.toByte(), '0'.code.toByte(), '0'.code.toByte(), '2'.code.toByte(), 0)

    fun encode(packet: AudioSyncPacket, out: ByteArray = ByteArray(PACKET_SIZE)): ByteArray {
        require(out.size >= PACKET_SIZE)
        out.fill(0, 0, PACKET_SIZE)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(HEADER)
        buf.position(8)
        buf.putFloat(packet.sampleRaw)
        buf.putFloat(packet.sampleSmth)
        buf.put(if (packet.samplePeak) 1 else 0)
        buf.position(18)
        for (v in packet.fftResult) buf.put(v.coerceIn(0, 254).toByte())
        buf.position(36)
        buf.putFloat(packet.fftMagnitude)
        buf.putFloat(packet.fftMajorPeak)
        return out
    }

    fun encode(features: AudioFeatures): ByteArray = encode(AudioSyncPacket.from(features))

    /** Декодирует пакет; null, если длина или заголовок не соответствуют v2. */
    fun decode(data: ByteArray, length: Int = data.size): AudioSyncPacket? {
        if (length != PACKET_SIZE || data.size < PACKET_SIZE) return null
        for (i in HEADER.indices) if (data[i] != HEADER[i]) return null
        val buf = ByteBuffer.wrap(data, 0, PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        return AudioSyncPacket(
            sampleRaw = buf.getFloat(8),
            sampleSmth = buf.getFloat(12),
            samplePeak = data[16].toInt() != 0,
            fftResult = IntArray(CHANNELS) { data[18 + it].toInt() and 0xFF },
            fftMagnitude = buf.getFloat(36),
            fftMajorPeak = buf.getFloat(40),
        )
    }
}
