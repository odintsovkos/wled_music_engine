package com.wledmusic.engine.core.network

/** Медианы задержки по этапам, мс; null — нет данных. */
data class LatencySnapshot(
    val audioToDspMs: Float?,
    /** Audio → DSP оценён по заполнению буфера, а не по временной метке AudioRecord. */
    val audioEstimated: Boolean,
    val dspToNetworkMs: Float?,
    /** Половина RTT запроса к лампе — всегда оценка. */
    val networkMs: Float?,
) {
    /** Сумма этапов «до лампы, без вывода на LED»; null, если какого-то этапа нет. */
    val totalMs: Float?
        get() = if (audioToDspMs == null || dspToNetworkMs == null || networkMs == null) null
        else audioToDspMs + dspToNetworkMs + networkMs

    enum class Stage { AUDIO_TO_DSP, DSP_TO_NETWORK, NETWORK }

    /** Наибольший этап — кандидат в причину высокой задержки. */
    val slowestStage: Stage?
        get() {
            val a = audioToDspMs ?: return null
            val d = dspToNetworkMs ?: return null
            val n = networkMs ?: return null
            return when (maxOf(a, d, n)) {
                n -> Stage.NETWORK
                d -> Stage.DSP_TO_NETWORK
                else -> Stage.AUDIO_TO_DSP
            }
        }
}

/**
 * Сбор задержек конвейера. [onAudio] и [onSend] вызываются из горячих потоков и не выделяют
 * память; [snapshot] (раз в секунду) считает медианы по последним [window] замерам.
 * RTT — медиана последних 5 замеров, как требует спецификация.
 */
class LatencyMonitor(private val window: Int = 64) {
    private val audio = Ring(window)
    private val send = Ring(window)
    private val rtt = Ring(5)
    @Volatile private var audioEstimated = false
    private var highSinceMs = -1L

    fun onAudio(captureToReadyNanos: Long, estimated: Boolean) {
        audioEstimated = estimated
        audio.add(captureToReadyNanos / 1e6f)
    }

    fun onSend(readyToSentNanos: Long) = send.add(readyToSentNanos / 1e6f)

    fun onRtt(rttMs: Float) = rtt.add(rttMs)

    fun reset() {
        audio.clear(); send.clear(); rtt.clear()
        highSinceMs = -1
    }

    fun snapshot(): LatencySnapshot = LatencySnapshot(
        audioToDspMs = audio.median(),
        audioEstimated = audioEstimated,
        dspToNetworkMs = send.median(),
        networkMs = rtt.median()?.let { it / 2f },
    )

    /**
     * Предупреждение: TOTAL выше [thresholdMs] дольше [holdMs]. Возвращает наибольший этап
     * или null, если предупреждать не о чем. Вызывать с каждым новым снимком.
     */
    fun warning(snapshot: LatencySnapshot, nowMs: Long, thresholdMs: Float = 100f, holdMs: Long = 5_000): LatencySnapshot.Stage? {
        val total = snapshot.totalMs
        if (total == null || total <= thresholdMs) {
            highSinceMs = -1
            return null
        }
        if (highSinceMs < 0) highSinceMs = nowMs
        return if (nowMs - highSinceMs >= holdMs) snapshot.slowestStage else null
    }

    private class Ring(size: Int) {
        private val values = FloatArray(size)
        private val scratch = FloatArray(size)
        private var count = 0
        private var pos = 0

        @Synchronized fun add(v: Float) {
            values[pos] = v
            pos = (pos + 1) % values.size
            if (count < values.size) count++
        }

        @Synchronized fun clear() { count = 0; pos = 0 }

        @Synchronized fun median(): Float? {
            if (count == 0) return null
            System.arraycopy(values, 0, scratch, 0, count)
            scratch.sort(0, count)
            return if (count % 2 == 1) scratch[count / 2] else (scratch[count / 2 - 1] + scratch[count / 2]) / 2f
        }
    }
}
