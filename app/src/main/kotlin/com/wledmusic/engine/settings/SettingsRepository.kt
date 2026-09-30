package com.wledmusic.engine.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wledmusic.engine.core.dsp.DspConfig
import com.wledmusic.engine.core.network.AudioSyncCodec
import com.wledmusic.engine.core.network.AudioSyncSender
import com.wledmusic.engine.core.network.LampJournal
import com.wledmusic.engine.core.network.LampJournalStore
import com.wledmusic.engine.core.render.AnimationDirection
import com.wledmusic.engine.core.render.EffectId
import com.wledmusic.engine.core.render.EffectParams
import com.wledmusic.engine.core.render.LedLayout
import com.wledmusic.engine.core.render.RenderLoop
import com.wledmusic.engine.core.render.SettingsCodec
import com.wledmusic.engine.session.SyncMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class LampSettings(
    val host: String = "",
    val port: Int = AudioSyncCodec.DEFAULT_PORT,
    val packetsPerSecond: Int = AudioSyncSender.DEFAULT_RATE,
)

/** Всё, что пользователь настраивает для RGB Engine и Pro Audio. */
data class EngineSettings(
    val mode: SyncMode = SyncMode.AUDIO_REACTIVE,
    val effect: EffectId = EffectId.SPECTRUM,
    val params: Map<EffectId, EffectParams> = emptyMap(),
    /** Последние выбранные эффекты, свежие первыми (для быстрых чипов на Sync). */
    val recentEffects: List<EffectId> = listOf(EffectId.SPECTRUM, EffectId.BEAT_FLASH, EffectId.AMBIENT_GLOW),
    val direction: AnimationDirection? = null,
    val fps: Int = RenderLoop.DEFAULT_FPS,
    val gamma: Boolean = false,
    val dsp: DspConfig = DspConfig(),
    val advancedExpanded: Boolean = false,
    /** Ручная раскладка для лампы [layoutHost]; null — автоопределение. */
    val layoutOverride: LedLayout? = null,
    val layoutHost: String? = null,
) {
    fun paramsFor(effect: EffectId): EffectParams = params[effect] ?: effect.defaultParams()
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Настройки приложения в DataStore. Отсутствующие и повреждённые значения — значения по умолчанию. */
class SettingsRepository(private val context: Context) : LampJournalStore {
    private object Keys {
        val host = stringPreferencesKey("lamp_host")
        val port = intPreferencesKey("lamp_port")
        val rate = intPreferencesKey("packets_per_second")
        val mode = stringPreferencesKey("sync_mode")
        val effect = stringPreferencesKey("effect")
        val recent = stringPreferencesKey("recent_effects")
        fun params(id: EffectId) = stringPreferencesKey("effect_params_${id.name}")
        val direction = stringPreferencesKey("animation_direction")
        val fps = intPreferencesKey("render_fps")
        val gamma = booleanPreferencesKey("render_gamma")
        val advanced = booleanPreferencesKey("effects_advanced")
        val layout = stringPreferencesKey("layout_override")
        val journal = stringPreferencesKey("lamp_journal")
        val attack = floatPreferencesKey("dsp_attack_ms")
        val release = floatPreferencesKey("dsp_release_ms")
        val bassStart = floatPreferencesKey("dsp_bass_start")
        val bassMid = floatPreferencesKey("dsp_bass_mid")
        val midHigh = floatPreferencesKey("dsp_mid_high")
        val highEnd = floatPreferencesKey("dsp_high_end")
        val beat = booleanPreferencesKey("dsp_beat")
        val agc = booleanPreferencesKey("dsp_agc")
        val gate = floatPreferencesKey("dsp_gate_db")
    }

    val lamp: Flow<LampSettings> = context.dataStore.data.map { p ->
        LampSettings(
            host = p[Keys.host] ?: "",
            port = p[Keys.port] ?: AudioSyncCodec.DEFAULT_PORT,
            packetsPerSecond = p[Keys.rate] ?: AudioSyncSender.DEFAULT_RATE,
        )
    }

    val engine: Flow<EngineSettings> = context.dataStore.data.map(::readEngine)

    suspend fun save(settings: LampSettings) {
        context.dataStore.edit { p ->
            p[Keys.host] = settings.host
            p[Keys.port] = settings.port
            p[Keys.rate] = settings.packetsPerSecond
        }
    }

    suspend fun updateEngine(transform: (EngineSettings) -> EngineSettings) {
        context.dataStore.edit { p -> writeEngine(p, transform(readEngine(p))) }
    }

    override suspend fun load(): LampJournal? = LampJournal.decode(context.dataStore.data.first()[Keys.journal])

    override suspend fun save(journal: LampJournal?) {
        context.dataStore.edit { p -> if (journal == null) p.remove(Keys.journal) else p[Keys.journal] = journal.encode() }
    }

    private fun readEngine(p: Preferences): EngineSettings {
        val defaults = EngineSettings()
        val layoutValue = p[Keys.layout]
        val sep = layoutValue?.lastIndexOf('|') ?: -1
        return EngineSettings(
            mode = p[Keys.mode]?.let { v -> SyncMode.values().firstOrNull { it.name == v } } ?: defaults.mode,
            effect = p[Keys.effect]?.let(::effectId) ?: defaults.effect,
            params = EffectId.values().mapNotNull { id -> SettingsCodec.decodeParams(p[Keys.params(id)])?.let { id to it } }.toMap(),
            recentEffects = p[Keys.recent]?.split(',')?.mapNotNull(::effectId)?.takeIf { it.isNotEmpty() } ?: defaults.recentEffects,
            direction = p[Keys.direction]?.let { v -> AnimationDirection.values().firstOrNull { it.name == v } },
            fps = p[Keys.fps]?.takeIf { it in RenderLoop.FPS_RANGE } ?: defaults.fps,
            gamma = p[Keys.gamma] ?: defaults.gamma,
            dsp = readDsp(p),
            advancedExpanded = p[Keys.advanced] ?: false,
            layoutOverride = if (sep > 0) SettingsCodec.decodeLayout(layoutValue!!.substring(sep + 1)) else null,
            layoutHost = if (sep > 0) layoutValue!!.substring(0, sep) else null,
        )
    }

    private fun readDsp(p: Preferences): DspConfig {
        val d = DspConfig()
        return runCatching {
            val bassMid = p[Keys.bassMid] ?: d.bassRange.endInclusive
            val midHigh = p[Keys.midHigh] ?: d.midRange.endInclusive
            d.copy(
                attackMs = p[Keys.attack] ?: d.attackMs,
                releaseMs = p[Keys.release] ?: d.releaseMs,
                bassRange = (p[Keys.bassStart] ?: d.bassRange.start)..bassMid,
                midRange = bassMid..midHigh,
                highRange = midHigh..(p[Keys.highEnd] ?: d.highRange.endInclusive),
                beatDetection = p[Keys.beat] ?: d.beatDetection,
                autoGain = p[Keys.agc] ?: d.autoGain,
                noiseGateDb = p[Keys.gate] ?: d.noiseGateDb,
            )
        }.getOrDefault(d)
    }

    private fun writeEngine(p: MutablePreferences, s: EngineSettings) {
        p[Keys.mode] = s.mode.name
        p[Keys.effect] = s.effect.name
        for ((id, params) in s.params) p[Keys.params(id)] = SettingsCodec.encodeParams(params)
        p[Keys.recent] = s.recentEffects.joinToString(",") { it.name }
        if (s.direction == null) p.remove(Keys.direction) else p[Keys.direction] = s.direction.name
        p[Keys.fps] = s.fps
        p[Keys.gamma] = s.gamma
        p[Keys.advanced] = s.advancedExpanded
        if (s.layoutOverride == null || s.layoutHost == null) p.remove(Keys.layout)
        else p[Keys.layout] = s.layoutHost + "|" + SettingsCodec.encodeLayout(s.layoutOverride)
        p[Keys.attack] = s.dsp.attackMs
        p[Keys.release] = s.dsp.releaseMs
        p[Keys.bassStart] = s.dsp.bassRange.start
        p[Keys.bassMid] = s.dsp.bassRange.endInclusive
        p[Keys.midHigh] = s.dsp.midRange.endInclusive
        p[Keys.highEnd] = s.dsp.highRange.endInclusive
        p[Keys.beat] = s.dsp.beatDetection
        p[Keys.agc] = s.dsp.autoGain
        p[Keys.gate] = s.dsp.noiseGateDb
    }

    private fun effectId(name: String): EffectId? = EffectId.values().firstOrNull { it.name == name }
}
