package com.wledmusic.engine

import android.app.Application
import android.net.ConnectivityManager
import com.wledmusic.engine.core.dsp.DspConfig
import com.wledmusic.engine.core.network.LampStateGuard
import com.wledmusic.engine.core.render.LedLayout
import com.wledmusic.engine.core.render.PreviewFrame
import com.wledmusic.engine.core.render.RenderSettings
import com.wledmusic.engine.lamp.LampConnection
import com.wledmusic.engine.lamp.LiveEngine
import com.wledmusic.engine.session.RestoreStatus
import com.wledmusic.engine.session.SessionStateRepository
import com.wledmusic.engine.settings.EngineSettings
import com.wledmusic.engine.settings.LampSettings
import com.wledmusic.engine.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WmeApplication : Application() {
    /** Живёт вместе с процессом: восстановление лампы после остановки службы доводится здесь. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val sessionRepository = SessionStateRepository()
    val settingsRepository by lazy { SettingsRepository(this) }
    val lampConnection by lazy { LampConnection(getSystemService(ConnectivityManager::class.java)) }
    val lampGuard by lazy { LampStateGuard(lampConnection, settingsRepository) }

    val engineSettings: StateFlow<EngineSettings> by lazy {
        settingsRepository.engine.stateIn(appScope, SharingStarted.Eagerly, EngineSettings())
    }
    val lampSettings: StateFlow<LampSettings> by lazy {
        settingsRepository.lamp.stateIn(appScope, SharingStarted.Eagerly, LampSettings())
    }

    /** Раскладка текущей лампы для RGB Engine. */
    val layout: StateFlow<LedLayout?> by lazy {
        combine(lampSettings, engineSettings, sessionRepository.state.map { it.lamp }) { lamp, engine, status ->
            LiveEngine.layout(lamp, engine, status)
        }.stateIn(appScope, SharingStarted.Eagerly, null)
    }

    /** Что рисует RGB Engine: меняется на лету из экранов Effects и ориентации. */
    val renderSettings: StateFlow<RenderSettings> by lazy {
        combine(engineSettings, layout) { engine, layout -> LiveEngine.render(engine, layout) }
            .stateIn(appScope, SharingStarted.Eagerly, RenderSettings())
    }

    val dspConfig: StateFlow<DspConfig> by lazy {
        engineSettings.map(LiveEngine::dsp).stateIn(appScope, SharingStarted.Eagerly, DspConfig())
    }

    /** Кадры предпросмотра работающего рендер-цикла; null — RGB Engine не запущен. */
    val previewSource = MutableStateFlow<StateFlow<PreviewFrame?>?>(null)

    override fun onCreate() {
        super.onCreate()
        // Незавершённый откат лампы после гибели процесса — при следующем запуске.
        appScope.launch(Dispatchers.IO) { restorePendingLamp() }
    }

    suspend fun restorePendingLamp() {
        if (settingsRepository.load() == null) return
        when (lampGuard.restorePending()) {
            is LampStateGuard.RestoreResult.Restored -> sessionRepository.onRestore(RestoreStatus.RESTORED)
            is LampStateGuard.RestoreResult.Deferred -> sessionRepository.onRestore(RestoreStatus.DEFERRED)
            LampStateGuard.RestoreResult.NothingToRestore -> Unit
        }
    }
}
