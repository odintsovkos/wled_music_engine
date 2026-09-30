package com.wledmusic.engine.core.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Проверка ввода адреса лампы и классификация адресов как локальных. */
object WledAddress {
    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    private val hostLabel = Regex("""^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$""")
    private val localSuffixes = listOf(".local", ".lan", ".home", ".home.arpa", ".internal")

    sealed interface Parsed {
        val host: String
        data class Ipv4(override val host: String, val octets: IntArray) : Parsed
        data class Hostname(override val host: String) : Parsed
    }

    /** Разбирает ввод пользователя: IPv4 или имя хоста. null — некорректный ввод. */
    fun parse(input: String): Parsed? {
        val s = input.trim()
        if (s.isEmpty() || s.length > 253) return null
        ipv4.matchEntire(s)?.let { m ->
            val octets = m.groupValues.drop(1).map { it.toInt() }
            if (octets.any { it > 255 }) return null
            if (m.groupValues.drop(1).any { it.length > 1 && it.startsWith("0") }) return null
            return Parsed.Ipv4(s, octets.toIntArray())
        }
        // Строка из цифр и точек, не ставшая IPv4, — ошибка, а не имя хоста.
        if (s.all { it.isDigit() || it == '.' }) return null
        val labels = s.trimEnd('.').split('.')
        if (labels.any { !hostLabel.matches(it) }) return null
        return Parsed.Hostname(s.trimEnd('.').lowercase())
    }

    /** Имя хоста выглядит как локальное (без домена или с локальным суффиксом). */
    fun isLocalHostname(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return '.' !in h || localSuffixes.any { h.endsWith(it) }
    }

    /**
     * Адрес относится к локальной сети: RFC 1918, link-local, loopback,
     * multicast 239.0.0.0/8 (administratively scoped), IPv6 ULA/link-local.
     */
    fun isLocal(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> {
            val b = address.address.map { it.toInt() and 0xFF }
            address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress || b[0] == 239
        }
        is Inet6Address -> address.isLinkLocalAddress || address.isLoopbackAddress ||
            address.isSiteLocalAddress || (address.address[0].toInt() and 0xFE) == 0xFC
        else -> false
    }
}
