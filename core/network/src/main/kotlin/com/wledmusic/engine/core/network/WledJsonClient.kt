package com.wledmusic.engine.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLConnection

/** Сведения об устройстве из `GET /json/info`. */
data class WledInfo(
    val name: String,
    val version: String,
    val ledCount: Int?,
    /** На лампе установлен usermod AudioReactive. */
    val hasAudioReactive: Boolean,
    /** Строка «Audio Source» из usermod, например "UDP sound sync - receiving". */
    val audioSource: String?,
) {
    /** Лампа настроена на приём UDP Sound Sync. null — неизвестно. */
    val audioSyncReceiveEnabled: Boolean?
        get() = audioSource?.startsWith("UDP sound sync")
}

sealed interface WledInfoResult {
    data class Success(val info: WledInfo) : WledInfoResult
    data class Failure(val reason: Reason, val message: String?) : WledInfoResult

    enum class Reason { TIMEOUT, NETWORK, INVALID_RESPONSE }
}

/**
 * Клиент WLED JSON API. Блокирующий: вызывать из IO-потока.
 * [openConnection] позволяет Android-коду открыть соединение через конкретную Wi-Fi-сеть.
 */
class WledJsonClient(
    private val timeoutMs: Int = 3_000,
    private val openConnection: (URL) -> URLConnection = { it.openConnection() },
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun info(host: String, httpPort: Int = 80): WledInfoResult {
        val started = System.nanoTime()
        val url = URL("http", host, httpPort, "/json/info")
        var connection: HttpURLConnection? = null
        return try {
            connection = openConnection(url) as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            connection.connect()
            val elapsed = ((System.nanoTime() - started) / 1_000_000).toInt()
            connection.readTimeout = (timeoutMs - elapsed).coerceAtLeast(1)
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return WledInfoResult.Failure(WledInfoResult.Reason.INVALID_RESPONSE, "HTTP $code")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseInfo(body)?.let { WledInfoResult.Success(it) }
                ?: WledInfoResult.Failure(WledInfoResult.Reason.INVALID_RESPONSE, "not a WLED /json/info response")
        } catch (e: SocketTimeoutException) {
            WledInfoResult.Failure(WledInfoResult.Reason.TIMEOUT, e.message)
        } catch (e: IOException) {
            WledInfoResult.Failure(WledInfoResult.Reason.NETWORK, e.message ?: e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    internal fun parseInfo(body: String): WledInfo? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val version = root.string("ver") ?: return null
        val usermods = root["u"] as? JsonObject
        val audioSource = (usermods?.get("Audio Source") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.joinToString("")
        return WledInfo(
            name = root.string("name") ?: "WLED",
            version = version,
            ledCount = (root["leds"] as? JsonObject)?.get("count")?.jsonPrimitive?.intOrNull,
            hasAudioReactive = usermods?.containsKey("AudioReactive") == true,
            audioSource = audioSource,
        )
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
