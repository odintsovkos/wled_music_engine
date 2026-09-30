package com.wledmusic.engine.core.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.management.ManagementFactory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class DdpSenderTest {
    private class Recording : DatagramTransport {
        val sent = ArrayList<ByteArray>()
        var fail = false
        override fun send(data: ByteArray) = send(data, data.size)
        override fun send(data: ByteArray, length: Int) {
            if (fail) throw IOException("Network is unreachable")
            sent += data.copyOf(length)
        }
        override fun close() {}
    }

    @Test
    fun sequenceAdvancesPerFrameAndWraps() {
        val t = Recording()
        val sender = DdpSender(t)
        val rgb = ByteArray(1024 * 3)
        repeat(16) { sender.send(rgb, 1024) }
        val seqs = t.sent.map { DdpCodec.decodeHeader(it)!!.sequence }
        assertEquals(48, seqs.size)
        assertEquals(listOf(1, 1, 1, 2, 2, 2), seqs.take(6))
        assertEquals(listOf(1, 1, 1), seqs.takeLast(3)) // 16-й кадр: после 15 снова 1
        assertEquals(16L, sender.framesSent.value)
    }

    @Test
    fun networkErrorIsReportedAndSendingContinues() {
        val t = Recording().apply { fail = true }
        val sender = DdpSender(t)
        val rgb = ByteArray(30)
        assertEquals(false, sender.send(rgb, 10))
        assertTrue(sender.status.value is SenderStatus.Error)
        t.fail = false
        assertEquals(true, sender.send(rgb, 10))
        assertEquals(SenderStatus.Sending, sender.status.value)
        assertEquals(1L, sender.framesSent.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun frameLargerThanCapacityIsRejected() {
        DdpSender(Recording(), maxLeds = 256).send(ByteArray(3000), 1000)
    }

    @Test
    fun sendAllocatesOnlyTheCounter() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return
        if (!bean.isThreadAllocatedMemorySupported) return
        val noop = object : DatagramTransport {
            override fun send(data: ByteArray) {}
            override fun send(data: ByteArray, length: Int) {}
            override fun close() {}
        }
        val sender = DdpSender(noop)
        val rgb = ByteArray(1024 * 3)
        repeat(1_000) { sender.send(rgb, 1024) }
        val tid = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(tid)
        repeat(1_000) { sender.send(rgb, 1024) }
        val perFrame = (bean.getThreadAllocatedBytes(tid) - before) / 1_000
        // Допускается только упаковка счётчика кадров в StateFlow<Long>.
        assertTrue("allocated $perFrame bytes/frame", perFrame <= 32)
    }

    @Test
    fun framesReachUdpReceiver() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { rx ->
            rx.soTimeout = 2_000
            UdpTransport(InetAddress.getLoopbackAddress(), rx.localPort).use { tx ->
                val rgb = ByteArray(600 * 3) { it.toByte() }
                DdpSender(tx).send(rgb, 600)
                val assembled = ByteArray(rgb.size)
                var push = false
                while (!push) {
                    val buf = ByteArray(1500)
                    val packet = DatagramPacket(buf, buf.size)
                    rx.receive(packet)
                    val h = DdpCodec.decodeHeader(buf, packet.length)!!
                    System.arraycopy(buf, DdpCodec.HEADER_SIZE, assembled, h.offset, h.length)
                    push = h.push
                }
                assertArrayEquals(rgb, assembled)
            }
        }
    }
}
