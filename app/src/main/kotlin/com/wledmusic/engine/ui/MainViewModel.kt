package com.wledmusic.engine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wledmusic.engine.core.network.AudioSyncSender
import com.wledmusic.engine.core.network.WledAddress
import com.wledmusic.engine.core.network.WledInfoResult
import com.wledmusic.engine.session.LampStatus
import com.wledmusic.engine.session.SessionStateRepository
import com.wledmusic.engine.settings.LampSettings
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Зависимости ViewModel, подменяемые в тестах. */
interface MainDependencies {
    val session: SessionStateRepository
    val savedSettings: Flow<LampSettings>
    suspend fun saveSettings(settings: LampSettings)
    /** Проверка лампы через JSON API (IO внутри). */
    suspend fun checkLamp(host: String): WledInfoResult
    /** Адрес принадлежит локальной сети (IO внутри). */
    suspend fun isLocalAddress(host: String): Boolean
}

data class MainUiState(
    val loaded: Boolean = false,
    val host: String = "",
    val port: String = "11988",
    val packetsPerSecond: Int = AudioSyncSender.DEFAULT_RATE,
    val confirmPublicAddress: Boolean = false,
) {
    val hostValid: Boolean get() = WledAddress.parse(host) != null
    val portValue: Int? get() = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val hostError: Boolean get() = host.isNotBlank() && !hostValid
    val portError: Boolean get() = portValue == null
    val canStart: Boolean get() = loaded && hostValid && portValue != null
}

/** Запрос Activity на запуск сессии: дальше — разрешения и системное согласие на захват. */
data class StartRequest(val host: String, val port: Int, val packetsPerSecond: Int)

class MainViewModel(private val deps: MainDependencies) : ViewModel() {
    private val _ui = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()
    val session = deps.session.state
    val features = deps.session.features

    private val _startRequests = Channel<StartRequest>(Channel.CONFLATED)
    val startRequests: Flow<StartRequest> = _startRequests.receiveAsFlow()

    init {
        viewModelScope.launch {
            val saved = deps.savedSettings.first()
            _ui.update {
                it.copy(loaded = true, host = saved.host, port = saved.port.toString(), packetsPerSecond = saved.packetsPerSecond)
            }
        }
    }

    fun onHostChange(value: String) = _ui.update { it.copy(host = value.trim()) }
    fun onPortChange(value: String) = _ui.update { it.copy(port = value.filter(Char::isDigit).take(5)) }
    fun onRateChange(value: Int) = _ui.update { it.copy(packetsPerSecond = value.coerceIn(AudioSyncSender.RATE_RANGE)) }

    fun checkLamp() {
        val host = WledAddress.parse(_ui.value.host)?.host ?: return
        deps.session.onLamp(LampStatus.Checking)
        viewModelScope.launch {
            persist()
            val status = when (val result = deps.checkLamp(host)) {
                is WledInfoResult.Success -> LampStatus.Available(result.info)
                is WledInfoResult.Failure -> LampStatus.Unavailable(result.reason, result.message)
            }
            deps.session.onLamp(status)
        }
    }

    /** Нажатие «Старт». Для публичного адреса сначала требуется подтверждение. */
    fun requestStart() {
        val state = _ui.value
        if (!state.canStart) return
        val host = WledAddress.parse(state.host)!!.host
        viewModelScope.launch {
            if (!deps.isLocalAddress(host)) {
                _ui.update { it.copy(confirmPublicAddress = true) }
            } else {
                emitStart()
            }
        }
    }

    fun confirmPublicAddress() {
        _ui.update { it.copy(confirmPublicAddress = false) }
        viewModelScope.launch { emitStart() }
    }

    fun dismissPublicAddress() = _ui.update { it.copy(confirmPublicAddress = false) }

    fun onPermissionDenied() = deps.session.onPermissionDenied()

    private suspend fun emitStart() {
        val state = _ui.value
        val host = WledAddress.parse(state.host)?.host ?: return
        val port = state.portValue ?: return
        persist()
        _startRequests.send(StartRequest(host, port, state.packetsPerSecond))
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
}
