package com.wledmusic.engine.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLConnection

/** Размер 2D-матрицы из `info.leds.matrix`. */
data class WledMatrix(val width: Int, val height: Int)

/** Сведения об устройстве из `GET /json/info`. */
data class WledInfo(
    val name: String,
    val version: String,
    val ledCount: Int?,
    /** На лампе установлен usermod AudioReactive. */
    val hasAudioReactive: Boolean,
    /** Строка «Audio Source» из usermod, например "UDP sound sync - receiving". */
    val audioSource: String?,
    /** 2D-матрица, настроенная в WLED; null — лента или 2D не настроено. */
    val matrix: WledMatrix? = null,
    /** Лампа сейчас в realtime-режиме (`info.live`). */
    val live: Boolean = false,
    /** Источник realtime, например "DDP" (`info.lm`). */
    val liveMode: String? = null,
    /** IP источника realtime (`info.lip`). */
    val liveIp: String? = null,
) {
    /** Лампа настроена на приём UDP Sound Sync. null — неизвестно. */
    val audioSyncReceiveEnabled: Boolean?
        get() = audioSource?.startsWith("UDP sound sync")
}

/** Сегмент из `/json/state` (только поля, которые показывает и восстанавливает приложение). */
data class WledSegment(
    val id: Int,
    val start: Int,
    val stop: Int,
    val effect: Int,
    val palette: Int,
    /** Первый цвет сегмента, 0xRRGGBB; null — нет цвета. */
    val primaryColor: Int?,
    val on: Boolean,
    val brightness: Int,
) {
    val length: Int get() = stop - start
}

/** Состояние из `GET /json/state`. */
data class WledState(
    val on: Boolean,
    val brightness: Int,
    /** Live override: 0 — выкл., 1 — до конца потока, 2 — до перезагрузки. */
    val liveOverride: Int,
    val preset: Int,
    val mainSegment: Int,
    val segments: List<WledSegment>,
) {
    val main: WledSegment? get() = segments.firstOrNull { it.id == mainSegment } ?: segments.firstOrNull()
}

sealed interface WledInfoResult {
    data class Success(val info: WledInfo) : WledInfoResult
    data class Failure(val reason: Reason, val message: String?) : WledInfoResult

    enum class Reason { TIMEOUT, NETWORK, INVALID_RESPONSE }
}

/** Результат запросов к WLED, кроме `/json/info` (исторически отдельный тип). */
sealed interface WledResult<out T> {
    data class Success<T>(val value: T) : WledResult<T>
    data class Failure(val reason: WledInfoResult.Reason, val message: String?) : WledResult<Nothing>

    fun getOrNull(): T? = (this as? Success)?.value
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

    fun info(host: String, httpPort: Int = 80): WledInfoResult =
        when (val r = request(host, httpPort, "/json/info", null).parse("/json/info", ::parseInfo)) {
            is WledResult.Success -> WledInfoResult.Success(r.value)
            is WledResult.Failure -> WledInfoResult.Failure(r.reason, r.message)
        }

    fun state(host: String, httpPort: Int = 80): WledResult<WledState> =
        request(host, httpPort, "/json/state", null).parse("/json/state", ::parseState)

    /**
     * `POST /json/state` с телом [body] (JSON-объект). Успех — лампа ответила 200 с JSON-объектом
     * (`{"success":true}` или новым состоянием при `"v": true`).
     */
    fun postState(host: String, body: String, httpPort: Int = 80): WledResult<Unit> =
        request(host, httpPort, "/json/state", body).parse("/json/state") { parseObject(it)?.let { } }

    /** Имена эффектов из `GET /json/eff`; индекс — id эффекта. */
    fun effectNames(host: String, httpPort: Int = 80): WledResult<List<String>> =
        request(host, httpPort, "/json/eff", null).parse("/json/eff") { body ->
            (runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray)
                ?.map { (it as? JsonPrimitive)?.contentOrNull ?: "" }
        }

    private inline fun <T : Any> WledResult<String>.parse(path: String, parser: (String) -> T?): WledResult<T> =
        when (this) {
            is WledResult.Success -> parser(value)?.let { WledResult.Success(it) }
                ?: WledResult.Failure(WledInfoResult.Reason.INVALID_RESPONSE, "not a WLED $path response")
            is WledResult.Failure -> this
        }

    /** HTTP-запрос с общим таймаутом [timeoutMs] на соединение и чтение; тело ответа при коде 200. */
    private fun request(host: String, httpPort: Int, path: String, postBody: String?): WledResult<String> {
        val started = System.nanoTime()
        val url = URL("http", host, httpPort, path)
        var connection: HttpURLConnection? = null
        return try {
            connection = openConnection(url) as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            if (postBody != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
            }
            connection.connect()
            if (postBody != null) connection.outputStream.use { it.write(postBody.toByteArray()) }
            val elapsed = ((System.nanoTime() - started) / 1_000_000).toInt()
            connection.readTimeout = (timeoutMs - elapsed).coerceAtLeast(1)
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return WledResult.Failure(WledInfoResult.Reason.INVALID_RESPONSE, "HTTP $code")
            }
            WledResult.Success(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (e: SocketTimeoutException) {
            WledResult.Failure(WledInfoResult.Reason.TIMEOUT, e.message)
        } catch (e: IOException) {
            WledResult.Failure(WledInfoResult.Reason.NETWORK, e.message ?: e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    internal fun parseInfo(body: String): WledInfo? {
        val root = parseObject(body) ?: return null
        val version = root.string("ver") ?: return null
        val usermods = root["u"] as? JsonObject
        val audioSource = (usermods?.get("Audio Source") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.joinToString("")
        val leds = root["leds"] as? JsonObject
        val matrix = (leds?.get("matrix") as? JsonObject)?.let { m ->
            val w = m.int("w") ?: return@let null
            val h = m.int("h") ?: return@let null
            if (w > 0 && h > 0) WledMatrix(w, h) else null
        }
        return WledInfo(
            name = root.string("name") ?: "WLED",
            version = version,
            ledCount = leds?.int("count"),
            hasAudioReactive = usermods?.containsKey("AudioReactive") == true,
            audioSource = audioSource,
            matrix = matrix,
            live = root.bool("live") ?: false,
            liveMode = root.string("lm")?.takeIf { it.isNotEmpty() },
            liveIp = root.string("lip")?.takeIf { it.isNotEmpty() && it != "0.0.0.0" },
        )
    }

    internal fun parseState(body: String): WledState? {
        val root = parseObject(body) ?: return null
        val on = root.bool("on") ?: return null
        val bri = root.int("bri") ?: return null
        val segments = (root["seg"] as? JsonArray)?.mapIndexedNotNull { index, element ->
            val s = element as? JsonObject ?: return@mapIndexedNotNull null
            val stop = s.int("stop") ?: return@mapIndexedNotNull null
            WledSegment(
                id = s.int("id") ?: index,
                start = s.int("start") ?: 0,
                stop = stop,
                effect = s.int("fx") ?: 0,
                palette = s.int("pal") ?: 0,
                primaryColor = (s["col"] as? JsonArray)?.firstOrNull()?.let(::parseColor),
                on = s.bool("on") ?: true,
                brightness = s.int("bri") ?: 255,
            )
        } ?: emptyList()
        return WledState(
            on = on,
            brightness = bri,
            liveOverride = root.int("lor") ?: 0,
            preset = root.int("ps") ?: -1,
            mainSegment = root.int("mainseg") ?: 0,
            segments = segments,
        )
    }

    /** Цвет как `[r, g, b(, w)]` или строка `"RRGGBB"`. */
    private fun parseColor(element: JsonElement): Int? = when (element) {
        is JsonArray -> if (element.size >= 3) {
            val c = element.take(3).map { (it as? JsonPrimitive)?.intOrNull ?: return null }
            (c[0].coerceIn(0, 255) shl 16) or (c[1].coerceIn(0, 255) shl 8) or c[2].coerceIn(0, 255)
        } else null
        is JsonPrimitive -> element.contentOrNull?.takeIf { it.length == 6 }?.toIntOrNull(16)
        else -> null
    }

    private fun parseObject(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
