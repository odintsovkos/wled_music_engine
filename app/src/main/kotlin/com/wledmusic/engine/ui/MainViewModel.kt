package com.wledmusic.engine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wledmusic.engine.core.dsp.DspConfig
import com.wledmusic.engine.core.network.AudioSyncSender
import com.wledmusic.engine.core.network.LampStateGuard
import com.wledmusic.engine.core.network.WledAddress
import com.wledmusic.engine.core.network.WledInfoResult
import com.wledmusic.engine.core.render.AnimationDirection
import com.wledmusic.engine.core.render.EffectId
import com.wledmusic.engine.core.render.EffectParams
import com.wledmusic.engine.core.render.LedLayout
import com.wledmusic.engine.core.render.PreviewFrame
import com.wledmusic.engine.core.render.RenderLoop
import com.wledmusic.engine.lamp.LiveEngine
import com.wledmusic.engine.session.LampStatus
import com.wledmusic.engine.session.SessionStateRepository
import com.wledmusic.engine.session.SyncMode
import com.wledmusic.engine.settings.EngineSettings
import com.wledmusic.engine.settings.LampSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Зависимости ViewModel, подменяемые в тестах. */
interface MainDependencies {
    val session: SessionStateRepository
    val savedSettings: Flow<LampSettings>
    val engineSettings: Flow<EngineSettings>
    /** Раскладка текущей лампы для RGB Engine (автоопределённая или ручная). */
    val layout: StateFlow<LedLayout?>
    val wifiConnected: Flow<Boolean>
    /** Кадры предпросмотра работающего RGB Engine; null — не запущен. */
    val previewSource: StateFlow<StateFlow<PreviewFrame?>?>
    suspend fun saveSettings(settings: LampSettings)
    suspend fun updateEngine(transform: (EngineSettings) -> EngineSettings)
    /** Проверка лампы: `/json/info`, `/json/state`, имена эффектов; заодно откат отложенного журнала. */
    suspend fun checkLamp(host: String): LampStatus
    /** Адрес принадлежит локальной сети (IO внутри). */
    suspend fun isLocalAddress(host: String): Boolean
    suspend fun prepareLamp(host: String, realtime: Boolean, allowOverrideChange: Boolean): LampStateGuard.PrepareResult
    /** Сессия так и не началась (отказ в разрешении): откатить изменения, сделанные при подготовке. */
    suspend fun cancelPrepared(host: String)
    /** Тестовый узор раскладки на лампе на [seconds] с; false — лампа не приняла или недоступна. */
    suspend fun showTestPattern(host: String, layout: LedLayout, direction: AnimationDirection, seconds: Int): Boolean
}

/** Почему старт не состоялся (показывается одной строкой на экране Sync). */
enum class StartError { LAMP_UNAVAILABLE, LAYOUT_UNKNOWN }

data class MainUiState(
    val loaded: Boolean = false,
    val host: String = "",
    val port: String = "11988",
    val packetsPerSecond: Int = AudioSyncSender.DEFAULT_RATE,
    val confirmPublicAddress: Boolean = false,
    /** На лампе включён live override (значение `lor`) — нужен ответ пользователя. */
    val overrideConsent: Int? = null,
    val startError: StartError? = null,
    val preparing: Boolean = false,
    val testPatternRunning: Boolean = false,
    val testPatternFailed: Boolean = false,
    val wifiConnected: Boolean = true,
) {
    val hostValid: Boolean get() = WledAddress.parse(host) != null
    val portValue: Int? get() = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val hostError: Boolean get() = host.isNotBlank() && !hostValid
    val portError: Boolean get() = portValue == null
}

/** Запрос Activity на запуск сессии: дальше — разрешения и системное согласие на захват. */
data class StartRequest(val host: String, val port: Int, val packetsPerSecond: Int, val mode: SyncMode = SyncMode.AUDIO_REACTIVE)

/** Готовность лампы к режимам: null — неизвестно (лампа не проверена). */
data class ModeReadiness(val audioReactive: Boolean?, val rgbEngine: Boolean?)

class MainViewModel(private val deps: MainDependencies) : ViewModel() {
    private val _ui = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()
    val session = deps.session.state
    val features = deps.session.features
    val engine: StateFlow<EngineSettings> = deps.engineSettings.stateIn(viewModelScope, SharingStarted.Eagerly, EngineSettings())
    val layout: StateFlow<LedLayout?> = deps.layout
    val previewSource: StateFlow<StateFlow<PreviewFrame?>?> = deps.previewSource

    private val _startRequests = Channel<StartRequest>(Channel.CONFLATED)
    val startRequests: Flow<StartRequest> = _startRequests.receiveAsFlow()

    private var pollJob: Job? = null
    private var preparedHost: String? = null

    init {
        viewModelScope.launch {
            val saved = deps.savedSettings.first()
            _ui.update {
                it.copy(loaded = true, host = saved.host, port = saved.port.toString(), packetsPerSecond = saved.packetsPerSecond)
            }
            deps.session.onModeSelected(engine.value.mode)
        }
        viewModelScope.launch { deps.wifiConnected.collect { c -> _ui.update { it.copy(wifiConnected = c) } } }
        viewModelScope.launch { deps.engineSettings.collect { deps.session.onModeSelected(it.mode) } }
    }

    val mode: SyncMode get() = engine.value.mode

    /** Можно ли нажать «Начать синхронизацию» (без учёта уже идущей сессии). */
    fun canStart(ui: MainUiState, engine: EngineSettings, lamp: LampStatus, layout: LedLayout?): Boolean {
        if (!ui.loaded || !ui.hostValid || ui.portValue == null || ui.preparing) return false
        if (lamp is LampStatus.Unavailable) return false
        return when (engine.mode) {
            SyncMode.AUDIO_REACTIVE -> true
            SyncMode.RGB_ENGINE -> lamp is LampStatus.Available && layout != null
        }
    }

    fun readiness(lamp: LampStatus): ModeReadiness {
        val available = lamp as? LampStatus.Available ?: return ModeReadiness(null, null)
        val info = available.info
        val ledsOk = LedLayout.detect(info.ledCount, info.matrix?.width, info.matrix?.height) != null
        return ModeReadiness(audioReactive = info.hasAudioReactive, rgbEngine = ledsOk)
    }

    fun onHostChange(value: String) = _ui.update { it.copy(host = value.trim(), startError = null) }
    fun onPortChange(value: String) {
        _ui.update { it.copy(port = value.filter(Char::isDigit).take(5)) }
        viewModelScope.launch { persist() }
    }
    fun onRateChange(value: Int) {
        _ui.update { it.copy(packetsPerSecond = value.coerceIn(AudioSyncSender.RATE_RANGE)) }
        viewModelScope.launch { persist() }
    }

    fun checkLamp() {
        val host = WledAddress.parse(_ui.value.host)?.host ?: return
        deps.session.onLamp(LampStatus.Checking)
        viewModelScope.launch {
            persist()
            deps.session.onLamp(deps.checkLamp(host))
        }
    }

    /** Экран виден: сразу проверяем лампу и затем обновляем карточку раз в 5 с. */
    fun onForeground(visible: Boolean) {
        pollJob?.cancel()
        if (!visible) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                val host = WledAddress.parse(_ui.value.host)?.host
                if (host != null && _ui.value.loaded && deps.session.state.value.lamp != LampStatus.Checking) {
                    val status = deps.checkLamp(host)
                    // Во время сессии не мигаем «Недоступна» из-за одного потерянного опроса.
                    if (status is LampStatus.Available || !deps.session.state.value.isRunning) deps.session.onLamp(status)
                }
                delay(LAMP_POLL_MS)
            }
        }
    }

    /** Нажатие «Начать синхронизацию». Для публичного адреса сначала требуется подтверждение. */
    fun requestStart() {
        val state = _ui.value
        val host = WledAddress.parse(state.host)?.host ?: return
        if (!canStart(state, engine.value, deps.session.state.value.lamp, layout.value)) return
        _ui.update { it.copy(startError = null) }
        viewModelScope.launch {
            if (!deps.isLocalAddress(host)) {
                _ui.update { it.copy(confirmPublicAddress = true) }
            } else {
                prepareAndStart(allowOverrideChange = false)
            }
        }
    }

    fun confirmPublicAddress() {
        _ui.update { it.copy(confirmPublicAddress = false) }
        viewModelScope.launch { prepareAndStart(allowOverrideChange = false) }
    }

    fun dismissPublicAddress() = _ui.update { it.copy(confirmPublicAddress = false) }

    fun confirmOverrideChange() {
        _ui.update { it.copy(overrideConsent = null) }
        viewModelScope.launch { prepareAndStart(allowOverrideChange = true) }
    }

    fun dismissOverrideChange() = _ui.update { it.copy(overrideConsent = null) }

    fun onPermissionDenied() {
        deps.session.onPermissionDenied()
        onStartCancelled()
    }

    /** Пользователь передумал до системных диалогов: откатить подготовку лампы без смены статуса. */
    fun onStartCancelled() {
        val host = preparedHost ?: return
        preparedHost = null
        viewModelScope.launch { deps.cancelPrepared(host) }
    }

    /** Сессия запущена службой: подготовленное состояние теперь восстанавливает служба. */
    fun onSessionHandedOver() {
        preparedHost = null
    }

    private suspend fun prepareAndStart(allowOverrideChange: Boolean) {
        val state = _ui.value
        val host = WledAddress.parse(state.host)?.host ?: return
        val port = state.portValue ?: return
        val mode = engine.value.mode
        persist()
        if (mode == SyncMode.RGB_ENGINE && layout.value == null) {
            _ui.update { it.copy(startError = StartError.LAYOUT_UNKNOWN) }
            return
        }
        _ui.update { it.copy(preparing = true) }
        val result = deps.prepareLamp(host, realtime = mode == SyncMode.RGB_ENGINE, allowOverrideChange)
        _ui.update { it.copy(preparing = false) }
        when (result) {
            is LampStateGuard.PrepareResult.Ready -> Unit
            is LampStateGuard.PrepareResult.NeedsOverrideConsent -> {
                _ui.update { it.copy(overrideConsent = result.liveOverride) }
                return
            }
            is LampStateGuard.PrepareResult.Unavailable -> {
                // Audio Reactive (Этап I) работает и без снимка: лампа могла не ответить по HTTP.
                if (mode == SyncMode.RGB_ENGINE) {
                    deps.session.onLamp(LampStatus.Unavailable(result.reason, result.message))
                    _ui.update { it.copy(startError = StartError.LAMP_UNAVAILABLE) }
                    return
                }
            }
        }
        preparedHost = host
        _startRequests.send(StartRequest(host, port, state.packetsPerSecond, mode))
    }

    // --- Режим, эффекты, раскладка, настройки: всё хранится в EngineSettings. ---

    fun selectMode(mode: SyncMode) {
        if (deps.session.state.value.isRunning) return
        edit { it.copy(mode = mode) }
    }

    fun selectEffect(effect: EffectId) = edit {
        it.copy(effect = effect, recentEffects = (listOf(effect) + it.recentEffects.filter { e -> e != effect }).take(RECENT_EFFECTS))
    }

    fun updateParams(transform: (EffectParams) -> EffectParams) = edit {
        val current = it.paramsFor(it.effect)
        it.copy(params = it.params + (it.effect to transform(current)))
    }

    fun setAdvancedExpanded(expanded: Boolean) = edit { it.copy(advancedExpanded = expanded) }

    fun setLayoutOverride(layout: LedLayout?) {
        val host = WledAddress.parse(_ui.value.host)?.host
        edit { it.copy(layoutOverride = layout, layoutHost = if (layout == null) null else host) }
    }

    fun setDirection(direction: AnimationDirection) = edit { it.copy(direction = direction) }

    fun resetLayout() = edit { it.copy(layoutOverride = null, layoutHost = null, direction = null) }

    fun setFps(fps: Int) = edit { it.copy(fps = fps.coerceIn(RenderLoop.FPS_RANGE)) }

    fun setGamma(enabled: Boolean) = edit { it.copy(gamma = enabled) }

    /** Параметры DSP из Pro Audio; некорректная комбинация отклоняется (false). */
    fun updateDsp(transform: (DspConfig) -> DspConfig): Boolean {
        val next = runCatching { transform(engine.value.dsp) }.getOrNull() ?: return false
        edit { it.copy(dsp = next) }
        return true
    }

    fun resetDsp() = edit { it.copy(dsp = DspConfig()) }

    /** Число LED по данным лампы (для предупреждения о несовпадении ручного размера). */
    fun lampLedCount(): Int? = (deps.session.state.value.lamp as? LampStatus.Available)?.info?.ledCount

    fun direction(): AnimationDirection = LiveEngine.direction(engine.value, layout.value)

    fun showTestPattern() {
        val host = WledAddress.parse(_ui.value.host)?.host ?: return
        val layout = layout.value ?: return
        if (deps.session.state.value.isRunning || _ui.value.testPatternRunning) return
        _ui.update { it.copy(testPatternRunning = true, testPatternFailed = false) }
        viewModelScope.launch {
            val ok = deps.showTestPattern(host, layout, direction(), TEST_PATTERN_SECONDS)
            _ui.update { it.copy(testPatternRunning = false, testPatternFailed = !ok) }
        }
    }

    fun dismissRestoreMessage() = deps.session.onRestore(com.wledmusic.engine.session.RestoreStatus.NONE)

    private fun edit(transform: (EngineSettings) -> EngineSettings) {
        viewModelScope.launch { deps.updateEngine(transform) }
    }

    private suspend fun persist() {
        val state = _ui.value
        deps.saveSettings(
            LampSettings(
                host = state.host,
                port = state.portValue ?: return,
                packetsPerSecond = state.packetsPerSecond,
            )
        )
    }

    companion object {
        const val LAMP_POLL_MS = 5_000L
        const val RECENT_EFFECTS = 3
        const val TEST_PATTERN_SECONDS = 10

        /** Статус проверки лампы из результата `/json/info` (для зависимостей и тестов). */
        fun lampStatus(result: WledInfoResult): LampStatus = when (result) {
            is WledInfoResult.Success -> LampStatus.Available(result.info)
            is WledInfoResult.Failure -> LampStatus.Unavailable(result.reason, result.message)
        }
    }
}
