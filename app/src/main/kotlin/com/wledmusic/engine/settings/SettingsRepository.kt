package com.wledmusic.engine.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wledmusic.engine.core.network.AudioSyncCodec
import com.wledmusic.engine.core.network.AudioSyncSender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class LampSettings(
    val host: String = "",
    val port: Int = AudioSyncCodec.DEFAULT_PORT,
    val packetsPerSecond: Int = AudioSyncSender.DEFAULT_RATE,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Последний использованный адрес лампы и параметры передачи. */
class SettingsRepository(private val context: Context) {
    private object Keys {
        val host = stringPreferencesKey("lamp_host")
        val port = intPreferencesKey("lamp_port")
        val rate = intPreferencesKey("packets_per_second")
    }

    val lamp: Flow<LampSettings> = context.dataStore.data.map { p ->
        LampSettings(
            host = p[Keys.host] ?: "",
            port = p[Keys.port] ?: AudioSyncCodec.DEFAULT_PORT,
            packetsPerSecond = p[Keys.rate] ?: AudioSyncSender.DEFAULT_RATE,
        )
    }

    suspend fun save(settings: LampSettings) {
        context.dataStore.edit { p ->
            p[Keys.host] = settings.host
            p[Keys.port] = settings.port
            p[Keys.rate] = settings.packetsPerSecond
        }
    }
}
