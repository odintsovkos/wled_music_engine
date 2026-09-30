package com.wledmusic.engine.service

import android.os.Process
import android.os.SystemClock
import com.wledmusic.engine.core.audio.FloatRingBuffer
import com.wledmusic.engine.core.audio.SilenceDetector
import com.wledmusic.engine.core.dsp.DspEngine
import com.wledmusic.engine.session.SessionStateRepository
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * DSP-поток: забирает блоки из кольцевого буфера захвата, считает признаки и публикует их.
 * Чтение аудио никогда не ждёт DSP; если DSP отстал, накопленные блоки пропускаются,
 * чтобы задержка не росла.
 *
 * [captureTimeNanos] переводит номер отсчёта от начала записи во время его захвата (null —
 * временная метка AudioRecord недоступна, тогда время оценивается по заполнению буфера).
 */
class AudioPipeline(
    private val buffer: FloatRingBuffer,
    private val engine: DspEngine,
    private val repository: SessionStateRepository,
    private val captureTimeNanos: (framePosition: Long) -> Long?,
    private val musicActive: () -> Boolean,
) {
    @Volatile private var running = false
    private var worker: Thread? = null

    fun start() {
        running = true
        worker = thread(name = "wme-dsp", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
        worker?.let {
            LockSupport.unpark(it)
            it.join(500)
        }
        worker = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val hop = engine.config.hopSize
        val maxBacklog = hop * MAX_BACKLOG_HOPS
        val block = FloatArray(hop)
        val detector = SilenceDetector()
        var lastSilent: Boolean? = null
        var lastBlocked = false
        var musicActiveCached = false
        var lastMusicPollMs = 0L
        var avgNanos = 0.0
        var lastTimingReportMs = 0L

        while (running) {
            val available = buffer.available()
            if (available < hop) {
                LockSupport.parkNanos(IDLE_PARK_NANOS)
                continue
            }
            if (available > maxBacklog) buffer.skip((available - hop) / hop * hop)
            val backlog = buffer.available() - hop
            if (!buffer.read(block, 0, hop)) continue

            val t0 = System.nanoTime()
            // Последний отсчёт блока: позиция чтения + отброшенные при переполнении отсчёты.
            val lastFrame = buffer.readPosition + buffer.dropped - 1
            val stamped = captureTimeNanos(lastFrame)
            val captureNanos = stamped ?: (t0 - backlog * 1_000_000_000L / engine.config.sampleRate)
            val features = engine.process(block)
            val ready = System.nanoTime()
            avgNanos = if (avgNanos == 0.0) (ready - t0).toDouble()
            else avgNanos * 0.98 + (ready - t0) * 0.02

            val now = SystemClock.elapsedRealtime()
            if (now - lastMusicPollMs >= MUSIC_POLL_MS) {
                musicActiveCached = musicActive()
                lastMusicPollMs = now
            }
            var digitalSilence = true
            for (s in block) if (abs(s) > DIGITAL_SILENCE) { digitalSilence = false; break }

            val result = detector.update(now, features.gateOpen, digitalSilence, musicActiveCached)
            if (result.silent != lastSilent || result.captureBlockedSuspected != lastBlocked) {
                repository.onSilenceChanged(result.silent, result.captureBlockedSuspected)
                lastSilent = result.silent
                lastBlocked = result.captureBlockedSuspected
            }
            repository.onFeatures(features, captureNanos, ready, estimated = stamped == null)

            if (now - lastTimingReportMs >= 1_000) {
                repository.onDspTiming((avgNanos / 1e6).toFloat())
                lastTimingReportMs = now
            }
        }
    }

    private companion object {
        const val MAX_BACKLOG_HOPS = 3
        const val IDLE_PARK_NANOS = 2_000_000L
        const val MUSIC_POLL_MS = 500L
        const val DIGITAL_SILENCE = 1e-7f
    }
}
