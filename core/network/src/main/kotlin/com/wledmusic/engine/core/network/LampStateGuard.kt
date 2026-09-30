package com.wledmusic.engine.core.network

import kotlinx.coroutines.delay

/** Доступ к состоянию лампы; в приложении — [WledJsonClient] через Wi-Fi-сеть. Блокирующий. */
interface LampApi {
    fun state(host: String): WledResult<WledState>
    fun postState(host: String, body: String): WledResult<Unit>
}

/**
 * Журнал изменений, внесённых приложением в runtime-состояние лампы.
 * [original] — значение до изменения, [applied] — установленное приложением.
 */
data class LampJournal(val host: String, val entries: List<Entry>) {
    data class Entry(val field: Field, val original: Int, val applied: Int)

    /** Поля `/json/state`, которые приложение может менять. Значения хранятся как Int. */
    enum class Field(val key: String) {
        LIVE_OVERRIDE("lor"), ON("on"), BRIGHTNESS("bri");

        fun read(state: WledState): Int = when (this) {
            LIVE_OVERRIDE -> state.liveOverride
            ON -> if (state.on) 1 else 0
            BRIGHTNESS -> state.brightness
        }

        fun json(value: Int): String = when (this) {
            ON -> """{"on":${value != 0}}"""
            else -> """{"$key":$value}"""
        }
    }

    /** Строка для хранения в DataStore: `host|lor:1:0,bri:128:255`. */
    fun encode(): String = host + "|" + entries.joinToString(",") { "${it.field.key}:${it.original}:${it.applied}" }

    companion object {
        fun decode(value: String?): LampJournal? {
            if (value.isNullOrEmpty()) return null
            val sep = value.lastIndexOf('|')
            if (sep <= 0) return null
            val entries = value.substring(sep + 1).split(',').filter { it.isNotEmpty() }.map { e ->
                val parts = e.split(':')
                if (parts.size != 3) return null
                val field = Field.values().firstOrNull { it.key == parts[0] } ?: return null
                Entry(field, parts[1].toIntOrNull() ?: return null, parts[2].toIntOrNull() ?: return null)
            }
            return LampJournal(value.substring(0, sep), entries)
        }
    }
}

/** Постоянное хранилище журнала (DataStore в приложении). */
interface LampJournalStore {
    suspend fun load(): LampJournal?
    suspend fun save(journal: LampJournal?)
}

/**
 * Снимок до сессии, журнал изменений и откат после неё.
 *
 * Правило отката: поле возвращается к исходному значению, только если на лампе всё ещё стоит
 * значение, установленное приложением; иначе его изменил пользователь, и поле не трогается.
 * Журнал сохраняется **до** изменения лампы, поэтому переживает гибель процесса.
 */
class LampStateGuard(
    private val api: LampApi,
    private val store: LampJournalStore,
    private val attempts: Int = 3,
    private val retryDelayMs: Long = 500,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    sealed interface PrepareResult {
        data class Ready(val snapshot: WledState) : PrepareResult
        /** На лампе включён live override: realtime-поток будет игнорироваться. */
        data class NeedsOverrideConsent(val liveOverride: Int) : PrepareResult
        data class Unavailable(val reason: WledInfoResult.Reason, val message: String?) : PrepareResult
    }

    sealed interface RestoreResult {
        data object NothingToRestore : RestoreResult
        data class Restored(val reverted: List<LampJournal.Field>, val keptUserChanges: List<LampJournal.Field>) : RestoreResult
        data class Deferred(val message: String?) : RestoreResult
    }

    /**
     * Перед сессией: откатывает незавершённый журнал, снимает состояние и, для realtime
     * ([realtime] = RGB Engine), при согласии [allowOverrideChange] выключает live override.
     */
    suspend fun prepare(host: String, realtime: Boolean, allowOverrideChange: Boolean): PrepareResult {
        store.load()?.let { restoreJournal(it, exitRealtime = false) }
        val snapshot = when (val r = api.state(host)) {
            is WledResult.Success -> r.value
            is WledResult.Failure -> return PrepareResult.Unavailable(r.reason, r.message)
        }
        if (!realtime || snapshot.liveOverride == 0) return PrepareResult.Ready(snapshot)
        if (!allowOverrideChange) return PrepareResult.NeedsOverrideConsent(snapshot.liveOverride)
        val field = LampJournal.Field.LIVE_OVERRIDE
        store.save(LampJournal(host, listOf(LampJournal.Entry(field, snapshot.liveOverride, 0))))
        return when (val r = api.postState(host, field.json(0))) {
            is WledResult.Success -> PrepareResult.Ready(snapshot.copy(liveOverride = 0))
            is WledResult.Failure -> PrepareResult.Unavailable(r.reason, r.message)
        }
    }

    /**
     * После сессии: для realtime ([exitRealtime]) — немедленный выход из realtime через
     * `{"live": false}`, затем откат журнала. При сетевых ошибках журнал остаётся отложенным.
     */
    suspend fun finish(host: String, exitRealtime: Boolean): RestoreResult {
        val journal = store.load()?.takeIf { it.host == host } ?: LampJournal(host, emptyList())
        if (!exitRealtime && journal.entries.isEmpty()) return RestoreResult.NothingToRestore
        return restoreJournal(journal, exitRealtime)
    }

    /** Откат незавершённого журнала (запуск приложения, возврат Wi-Fi, проверка лампы). */
    suspend fun restorePending(): RestoreResult {
        val journal = store.load() ?: return RestoreResult.NothingToRestore
        return restoreJournal(journal, exitRealtime = false)
    }

    private suspend fun restoreJournal(journal: LampJournal, exitRealtime: Boolean): RestoreResult {
        val reverted = ArrayList<LampJournal.Field>()
        val kept = ArrayList<LampJournal.Field>()
        var liveOff = !exitRealtime
        var lastError: String? = null
        repeat(attempts) { attempt ->
            if (attempt > 0) sleep(retryDelayMs)
            if (!liveOff) {
                when (val r = api.postState(journal.host, """{"live":false}""")) {
                    is WledResult.Success -> liveOff = true
                    is WledResult.Failure -> { lastError = r.message ?: r.reason.name; return@repeat }
                }
            }
            val state = when (val r = api.state(journal.host)) {
                is WledResult.Success -> r.value
                is WledResult.Failure -> { lastError = r.message ?: r.reason.name; return@repeat }
            }
            for (entry in journal.entries) {
                if (entry.field in reverted || entry.field in kept) continue
                if (entry.field.read(state) != entry.applied) {
                    kept += entry.field
                    continue
                }
                when (val r = api.postState(journal.host, entry.field.json(entry.original))) {
                    is WledResult.Success -> reverted += entry.field
                    is WledResult.Failure -> { lastError = r.message ?: r.reason.name; return@repeat }
                }
            }
            store.save(null)
            return RestoreResult.Restored(reverted, kept)
        }
        return RestoreResult.Deferred(lastError)
    }
}
