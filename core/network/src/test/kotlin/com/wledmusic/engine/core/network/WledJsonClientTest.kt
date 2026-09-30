package com.wledmusic.engine.core.network

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

class WledJsonClientTest {
    private var server: HttpServer? = null

    private fun serve(status: Int = 200, delayMs: Long = 0, body: () -> String): Int {
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        s.createContext("/json/info") { ex ->
            Thread.sleep(delayMs)
            val bytes = body().toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return s.address.port
    }

    @After
    fun stop() {
        server?.stop(0)
    }

    private val wledInfo = """
        {"ver":"0.16.0","vid":2607260,"name":"Kitchen","leds":{"count":120,"pwr":0},
         "u":{"AudioReactive":["<button>on</button>"],"Audio Source":["UDP sound sync"," - receiving"]},
         "arch":"esp32"}
    """.trimIndent()

    @Test
    fun parsesWledInfo() {
        val port = serve { wledInfo }
        val result = WledJsonClient().info("127.0.0.1", port)
        val info = (result as WledInfoResult.Success).info
        assertEquals("Kitchen", info.name)
        assertEquals("0.16.0", info.version)
        assertEquals(120, info.ledCount)
        assertTrue(info.hasAudioReactive)
        assertEquals("UDP sound sync - receiving", info.audioSource)
        assertEquals(true, info.audioSyncReceiveEnabled)
    }

    @Test
    fun deviceWithoutUsermods() {
        val port = serve { """{"ver":"0.15.1","name":"Strip","leds":{"count":60}}""" }
        val info = (WledJsonClient().info("127.0.0.1", port) as WledInfoResult.Success).info
        assertEquals(false, info.hasAudioReactive)
        assertEquals(null, info.audioSyncReceiveEnabled)
    }

    @Test
    fun garbageIsInvalidResponse() {
        val port = serve { "<html>router login</html>" }
        val result = WledJsonClient().info("127.0.0.1", port) as WledInfoResult.Failure
        assertEquals(WledInfoResult.Reason.INVALID_RESPONSE, result.reason)
    }

    @Test
    fun httpErrorIsInvalidResponse() {
        val port = serve(status = 404) { "not found" }
        val result = WledJsonClient().info("127.0.0.1", port) as WledInfoResult.Failure
        assertEquals(WledInfoResult.Reason.INVALID_RESPONSE, result.reason)
    }

    @Test
    fun slowDeviceTimesOut() {
        val port = serve(delayMs = 1_500) { wledInfo }
        val started = System.currentTimeMillis()
        val result = WledJsonClient(timeoutMs = 500).info("127.0.0.1", port) as WledInfoResult.Failure
        assertEquals(WledInfoResult.Reason.TIMEOUT, result.reason)
        assertTrue(System.currentTimeMillis() - started < 1_400)
    }

    @Test
    fun refusedConnectionIsNetworkError() {
        val freePort = ServerSocket(0).use { it.localPort }
        val result = WledJsonClient().info("127.0.0.1", freePort) as WledInfoResult.Failure
        assertEquals(WledInfoResult.Reason.NETWORK, result.reason)
    }
}
