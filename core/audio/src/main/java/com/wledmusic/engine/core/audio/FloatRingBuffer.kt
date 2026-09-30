package com.wledmusic.engine.core.audio

import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free кольцевой буфер float-отсчётов для одного писателя и одного читателя.
 * Писатель (поток AudioRecord) никогда не ждёт: если буфер полон, лишние отсчёты отбрасываются
 * и учитываются в [dropped]. Читатель может пропустить накопившиеся данные через [skip],
 * чтобы не копить задержку.
 */
class FloatRingBuffer(capacity: Int) {
    init {
        require(capacity >= 2 && capacity and (capacity - 1) == 0) { "capacity must be a power of two" }
    }

    private val data = FloatArray(capacity)
    private val mask = capacity - 1
    private val writePos = AtomicLong(0)
    private val readPos = AtomicLong(0)
    private val droppedCount = AtomicLong(0)

    val capacity: Int get() = data.size

    /** Отсчёты, отброшенные из-за переполнения. */
    val dropped: Long get() = droppedCount.get()

    /** Сколько отсчётов прочитано или пропущено читателем с начала. */
    val readPosition: Long get() = readPos.get()

    fun available(): Int = (writePos.get() - readPos.get()).toInt()

    /** Только писатель. Возвращает число записанных отсчётов. */
    fun write(src: FloatArray, offset: Int = 0, length: Int = src.size - offset): Int {
        val w = writePos.get()
        val free = data.size - (w - readPos.get()).toInt()
        val n = minOf(length, free)
        for (i in 0 until n) data[((w + i) and mask.toLong()).toInt()] = src[offset + i]
        writePos.set(w + n)
        if (n < length) droppedCount.addAndGet((length - n).toLong())
        return n
    }

    /** Только читатель. Читает ровно [length] отсчётов, если они есть; иначе false. */
    fun read(dst: FloatArray, offset: Int = 0, length: Int): Boolean {
        val r = readPos.get()
        if ((writePos.get() - r) < length) return false
        for (i in 0 until length) dst[offset + i] = data[((r + i) and mask.toLong()).toInt()]
        readPos.set(r + length)
        return true
    }

    /** Только читатель. Пропускает до [count] отсчётов. */
    fun skip(count: Int) {
        val n = minOf(count, available())
        readPos.addAndGet(n.toLong())
    }
}
