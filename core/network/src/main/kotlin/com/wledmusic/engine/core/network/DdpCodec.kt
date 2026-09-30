package com.wledmusic.engine.core.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Пакеты DDP v1 (Distributed Display Protocol, http://www.3waylabs.com/ddp/) с RGB-данными.
 *
 * | offset | поле        | значение                                         |
 * |-------:|-------------|--------------------------------------------------|
 * | 0      | flags       | `0x40` (версия 1) \| `0x01` (push, последний пакет) |
 * | 1      | sequence    | 1..15 по кругу, общий для всех пакетов кадра     |
 * | 2      | data type   | `0x0B` — RGB, 8 бит на канал                     |
 * | 3      | destination | `0x01` — основной дисплей                        |
 * | 4      | offset      | uint32 BE, смещение данных в байтах              |
 * | 8      | length      | uint16 BE, длина данных в байтах                 |
 * | 10     | data        | RGB                                              |
 *
 * WLED выводит кадр по флагу push, поэтому кадр из нескольких пакетов не показывается «половинами».
 */
object DdpCodec {
    const val PORT = 4048
    const val HEADER_SIZE = 10
    /** 480 LED × 3 байта: пакет вместе с IP/UDP-заголовками меньше MTU 1500. */
    const val MAX_DATA_SIZE = 1440
    const val FLAG_VERSION_1 = 0x40
    const val FLAG_PUSH = 0x01
    const val TYPE_RGB8 = 0x0B
    const val DESTINATION_DISPLAY = 0x01

    /** Число пакетов для кадра из [ledCount] LED. */
    fun packetCount(ledCount: Int): Int = maxOf(1, (ledCount * 3 + MAX_DATA_SIZE - 1) / MAX_DATA_SIZE)

    /** Размер пакета [index] кадра из [ledCount] LED (заголовок + данные). */
    fun packetSize(ledCount: Int, index: Int): Int {
        val dataSize = ledCount * 3
        return HEADER_SIZE + minOf(MAX_DATA_SIZE, dataSize - index * MAX_DATA_SIZE)
    }

    /**
     * Кодирует пакет [index] кадра [rgb] (первые `ledCount * 3` байт) в [out] и возвращает его длину.
     * [sequence] — 1..15, одинаковый для всех пакетов кадра.
     */
    fun encodePacket(rgb: ByteArray, ledCount: Int, index: Int, sequence: Int, out: ByteArray): Int {
        val dataSize = ledCount * 3
        require(ledCount > 0 && rgb.size >= dataSize) { "frame is smaller than $ledCount LEDs" }
        require(index in 0 until packetCount(ledCount)) { "packet index $index out of range" }
        require(sequence in 1..15) { "sequence must be 1..15" }
        val offset = index * MAX_DATA_SIZE
        val length = minOf(MAX_DATA_SIZE, dataSize - offset)
        require(out.size >= HEADER_SIZE + length) { "output buffer too small" }
        val last = offset + length == dataSize
        // Без ByteBuffer: метод вызывается на каждый пакет рендер-цикла и не должен выделять память.
        out[0] = (FLAG_VERSION_1 or if (last) FLAG_PUSH else 0).toByte()
        out[1] = sequence.toByte()
        out[2] = TYPE_RGB8.toByte()
        out[3] = DESTINATION_DISPLAY.toByte()
        out[4] = (offset ushr 24).toByte()
        out[5] = (offset ushr 16).toByte()
        out[6] = (offset ushr 8).toByte()
        out[7] = offset.toByte()
        out[8] = (length ushr 8).toByte()
        out[9] = length.toByte()
        System.arraycopy(rgb, offset, out, HEADER_SIZE, length)
        return HEADER_SIZE + length
    }

    /** Разобранный заголовок; используется в тестах и эмуляторе. */
    class Header(val push: Boolean, val sequence: Int, val dataType: Int, val offset: Int, val length: Int)

    fun decodeHeader(packet: ByteArray, size: Int = packet.size): Header? {
        if (size < HEADER_SIZE) return null
        val flags = packet[0].toInt() and 0xFF
        if (flags and 0xC0 != FLAG_VERSION_1) return null
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        val length = buf.getShort(8).toInt() and 0xFFFF
        if (HEADER_SIZE + length != size) return null
        return Header(
            push = flags and FLAG_PUSH != 0,
            sequence = packet[1].toInt() and 0x0F,
            dataType = packet[2].toInt() and 0xFF,
            offset = buf.getInt(4),
            length = length,
        )
    }
}
