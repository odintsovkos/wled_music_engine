package com.wledmusic.engine.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class WledAddressTest {
    @Test
    fun validInputs() {
        listOf("192.168.1.50", "10.0.0.1", "4.3.2.1", "wled-kitchen", "wled.local", " 192.168.4.1 ").forEach {
            assertNotNull(it, WledAddress.parse(it))
        }
        assertEquals("192.168.1.50", WledAddress.parse(" 192.168.1.50 ")!!.host)
    }

    @Test
    fun invalidInputs() {
        listOf("", "   ", "256.1.1.1", "192.168.1", "192.168.1.1.1", "01.2.3.4", "http://1.2.3.4", "a b", "-wled", "wled_1", "1.2.3.4:80")
            .forEach { assertNull(it, WledAddress.parse(it)) }
    }

    @Test
    fun localClassification() {
        val table = mapOf(
            "192.168.1.10" to true,
            "10.1.2.3" to true,
            "172.16.0.1" to true,
            "172.31.255.254" to true,
            "169.254.10.10" to true,
            "239.0.0.1" to true,
            "127.0.0.1" to true,
            "172.32.0.1" to false,
            "8.8.8.8" to false,
            "100.64.0.1" to false,
            "224.0.0.1" to false,
        )
        table.forEach { (ip, local) -> assertEquals(ip, local, WledAddress.isLocal(InetAddress.getByName(ip))) }
    }

    @Test
    fun localHostnames() {
        assertEquals(true, WledAddress.isLocalHostname("wled"))
        assertEquals(true, WledAddress.isLocalHostname("wled.local"))
        assertEquals(false, WledAddress.isLocalHostname("example.com"))
    }
}
