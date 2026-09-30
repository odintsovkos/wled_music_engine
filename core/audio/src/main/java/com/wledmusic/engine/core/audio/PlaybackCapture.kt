package com.wledmusic.engine.core.audio

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlin.concurrent.thread

/**
 * Захват системного воспроизведения через AudioPlaybackCapture.
 * Поток чтения с приоритетом URGENT_AUDIO сводит стерео в моно и пишет в [buffer].
 * Аудио никуда не сохраняется: данные живут только в кольцевом буфере в памяти.
 */
class PlaybackCapture(
    private val projection: MediaProjection,
    val sampleRate: Int = 48_000,
    private val blockFrames: Int = 1024,
    private val onError: (String) -> Unit,
) {
    /** ~0.7 с моно-аудио при 48 кГц. */
    val buffer = FloatRingBuffer(32_768)

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var reader: Thread? = null

    /** Кодировка, с которой удалось открыть AudioRecord. */
    var encoding: Int = AudioFormat.ENCODING_INVALID
        private set

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        check(!running) { "already started" }
        val rec = createRecord(AudioFormat.ENCODING_PCM_FLOAT) ?: createRecord(AudioFormat.ENCODING_PCM_16BIT)
            ?: throw IllegalStateException("AudioRecord for playback capture could not be initialized")
        encoding = rec.audioFormat
        record = rec
        rec.startRecording()
        running = true
        reader = thread(name = "wme-audio-capture", isDaemon = true) { readLoop(rec) }
    }

    fun stop() {
        running = false
        record?.let { rec ->
            runCatching { rec.stop() }
            reader?.join(500)
            rec.release()
        }
        record = null
        reader = null
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createRecord(encoding: Int): AudioRecord? {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, encoding)
        val bufferBytes = maxOf(minBuffer, blockFrames * 2 * bytesPerSample * 4)
        return try {
            val rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (rec.state == AudioRecord.STATE_INITIALIZED) rec else {
                rec.release()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord init failed for encoding $encoding", e)
            null
        }
    }

    private fun readLoop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val mono = FloatArray(blockFrames)
        val isFloat = rec.audioFormat == AudioFormat.ENCODING_PCM_FLOAT
        val floatIn = if (isFloat) FloatArray(blockFrames * 2) else null
        val shortIn = if (!isFloat) ShortArray(blockFrames * 2) else null

        while (running) {
            val read = if (floatIn != null) {
                rec.read(floatIn, 0, floatIn.size, AudioRecord.READ_BLOCKING)
            } else {
                rec.read(shortIn!!, 0, shortIn.size, AudioRecord.READ_BLOCKING)
            }
            if (!running) break
            if (read < 0) {
                running = false
                onError("AudioRecord.read error $read")
                break
            }
            val frames = read / 2
            if (floatIn != null) {
                for (i in 0 until frames) mono[i] = 0.5f * (floatIn[2 * i] + floatIn[2 * i + 1])
            } else {
                val s = shortIn!!
                for (i in 0 until frames) mono[i] = (s[2 * i] + s[2 * i + 1]) / 65_536f
            }
            buffer.write(mono, 0, frames)
        }
    }

    private companion object {
        const val TAG = "PlaybackCapture"
    }
}
