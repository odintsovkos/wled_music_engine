package com.wledmusic.engine.core.network

import com.wledmusic.engine.core.dsp.AudioFeatures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AudioSyncSenderTest {
    private fun frame(level: Float) = AudioFeatures(
        rms = level, rawLevel = level, level = level, bass = 0f, mid = 0f, high = 0f,
        bands = FloatArray(16), peak = false, majorPeakHz = 100f, majorPeakMagnitude = 0f,
        majorPeakLevel = 0f, zeroCrossings = 0, gateOpen = true,
    )

    private class RecordingTransport : DatagramTransport {
        val sent = CopyOnWriteArrayList<ByteArray>()
        var failNext = false
        override fun send(data: ByteArray) {
            if (failNext) {
                failNext = false
                throw IOException("Network is unreachable")
            }
            sent += data.copyOf()
        }
        override fun close() {}
    }

    @Test
    fun rateIsLimited() = runTest {
        val transport = RecordingTransport()
        val sender = AudioSyncSender(transport, packetsPerSecond = 50)
        val frames = MutableStateFlow<AudioFeatures?>(null)
        // DSP выдаёт кадры каждые 5 мс — в 4 раза чаще лимита.
        val producer = launch { var i = 0; while (true) { frames.value = frame((i++ % 100) / 100f); delay(5) } }
        val job = launch { sender.run(frames) }
        advanceTimeBy(10_000)
        job.cancelAndJoin(); producer.cancelAndJoin()
        assertTrue("sent ${transport.sent.size}", transport.sent.size <= 50 * 10 + 1)
        assertTrue("sent ${transport.sent.size}", transport.sent.size >= 400)
    }

    @Test
    fun sameFrameIsNotResent() = runTest {
        val transport = RecordingTransport()
        val sender = AudioSyncSender(transport)
        val frames = MutableStateFlow<AudioFeatures?>(frame(0.5f))
        val job = launch { sender.run(frames) }
        advanceTimeBy(1_000)
        job.cancelAndJoin()
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun networkErrorDoesNotStopSending() = runTest {
        val transport = RecordingTransport().apply { failNext = true }
        val sender = AudioSyncSender(transport)
        val frames = MutableStateFlow<AudioFeatures?>(frame(0.1f))
        val job = launch { sender.run(frames) }
        advanceTimeBy(30)
        assertTrue(sender.status.value is SenderStatus.Error)
        frames.value = frame(0.2f)
        advanceTimeBy(30)
        assertEquals(SenderStatus.Sending, sender.status.value)
        assertEquals(1L, sender.packetsSent.value)
        job.cancelAndJoin()
        assertEquals(SenderStatus.Idle, sender.status.value)
    }

    @Test
    fun blockedSendDoesNotQueueStaleFrames() = runBlocking {
        val unblock = CountDownLatch(1)
        val firstSendStarted = CountDownLatch(1)
        val levels = CopyOnWriteArrayList<Float>()
        val transport = object : DatagramTransport {
            override fun send(data: ByteArray) {
                levels += AudioSyncCodec.decode(data)!!.sampleSmth / 255f
                firstSendStarted.countDown()
                unblock.await(5, TimeUnit.SECONDS)
            }
            override fun close() {}
        }
        val sender = AudioSyncSender(transport)
        val frames = MutableStateFlow<AudioFeatures?>(frame(0.01f))
        val job = launch(Dispatchers.IO) { sender.run(frames) }
        assertTrue(firstSendStarted.await(2, TimeUnit.SECONDS))
        // Пока отправка заблокирована, DSP выдаёт ещё 50 кадров.
        for (i in 2..50) frames.value = frame(i / 100f)
        unblock.countDown()
        withTimeout(2_000) { while (levels.size < 2) delay(5) }
        job.cancelAndJoin()
        assertEquals(2, levels.size)
        assertEquals(0.5f, levels[1], 0.01f)
    }

    @Test
    fun packetsReachEmulatedWled() = runBlocking {
        FakeWledReceiver().use { wled ->
            UdpTransport(wled.address, wled.port).use { transport ->
                val sender = AudioSyncSender(transport)
                val frames = MutableStateFlow<AudioFeatures?>(null)
                val job = launch(Dispatchers.IO) { sender.run(frames) }
                for (i in 1..5) {
                    frames.value = frame(i / 10f)
                    delay(40)
                }
                assertTrue(wled.awaitPackets(5))
                job.cancelAndJoin()
                assertEquals(0, wled.rejected)
                assertEquals(0.5f * 255f, wled.accepted.last().sampleSmth, 1e-3f)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rateOutOfRangeIsRejected() {
        AudioSyncSender(RecordingTransport(), packetsPerSecond = 100)
    }

    @Test
    fun multicastTransportCanBeCreated() {
        UdpTransport(InetAddress.getByName(AudioSyncCodec.MULTICAST_ADDRESS), AudioSyncCodec.DEFAULT_PORT).close()
    }
}
