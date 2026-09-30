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

    private val posted = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun serve(status: Int = 200, delayMs: Long = 0, path: String = "/json/info", body: () -> String): Int {
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        s.createContext(path) { ex ->
            Thread.sleep(delayMs)
            if (ex.requestMethod == "POST") posted += ex.requestBody.bufferedReader().readText()
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

    @Test
    fun parsesMatrixAndLiveFields() {
        val port = serve {
            """{"ver":"16.0.0","name":"WLED","leds":{"count":256,"matrix":{"w":16,"h":16}},
               "live":true,"lm":"DDP","lip":"192.168.1.200"}"""
        }
        val info = (WledJsonClient().info("127.0.0.1", port) as WledInfoResult.Success).info
        assertEquals(WledMatrix(16, 16), info.matrix)
        assertTrue(info.live)
        assertEquals("DDP", info.liveMode)
        assertEquals("192.168.1.200", info.liveIp)
    }

    @Test
    fun idleLiveFieldsAreNull() {
        val port = serve { """{"ver":"16.0.0","leds":{"count":60},"live":false,"lm":"","lip":""}""" }
        val info = (WledJsonClient().info("127.0.0.1", port) as WledInfoResult.Success).info
        assertEquals(null, info.matrix)
        assertEquals(false, info.live)
        assertEquals(null, info.liveMode)
        assertEquals(null, info.liveIp)
    }

    @Test
    fun parsesState() {
        val port = serve(path = "/json/state") {
            """{"on":true,"bri":128,"transition":7,"ps":3,"pl":-1,"lor":0,"mainseg":1,
               "seg":[{"id":0,"start":0,"stop":10,"fx":0,"pal":0,"col":[[255,0,0],[0,0,0],[0,0,0]],"on":true,"bri":255},
                      {"id":1,"start":10,"stop":256,"fx":110,"pal":11,"col":["00FF80","000000","000000"],"on":true,"bri":200}]}"""
        }
        val state = (WledJsonClient().state("127.0.0.1", port) as WledResult.Success).value
        assertEquals(true, state.on)
        assertEquals(128, state.brightness)
        assertEquals(3, state.preset)
        assertEquals(0, state.liveOverride)
        assertEquals(2, state.segments.size)
        assertEquals(0xFF0000, state.segments[0].primaryColor)
        val main = state.main!!
        assertEquals(1, main.id)
        assertEquals(246, main.length)
        assertEquals(110, main.effect)
        assertEquals(0x00FF80, main.primaryColor)
    }

    @Test
    fun postsState() {
        val port = serve(path = "/json/state") { """{"success":true}""" }
        val result = WledJsonClient().postState("127.0.0.1", """{"live":false}""", port)
        assertTrue(result is WledResult.Success)
        assertEquals(listOf("""{"live":false}"""), posted)
    }

    @Test
    fun postStateHttpErrorIsFailure() {
        val port = serve(status = 400, path = "/json/state") { """{"error":9}""" }
        val result = WledJsonClient().postState("127.0.0.1", "{}", port) as WledResult.Failure
        assertEquals(WledInfoResult.Reason.INVALID_RESPONSE, result.reason)
    }

    @Test
    fun parsesEffectNames() {
        val port = serve(path = "/json/eff") { """["Solid","Blink","Breathe"]""" }
        val names = (WledJsonClient().effectNames("127.0.0.1", port) as WledResult.Success).value
        assertEquals(listOf("Solid", "Blink", "Breathe"), names)
    }
}
