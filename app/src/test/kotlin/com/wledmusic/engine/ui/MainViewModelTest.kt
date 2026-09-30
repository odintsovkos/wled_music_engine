package com.wledmusic.engine.ui

import com.wledmusic.engine.core.dsp.DspConfig
import com.wledmusic.engine.core.network.LampStateGuard
import com.wledmusic.engine.core.network.WledInfo
import com.wledmusic.engine.core.network.WledInfoResult
import com.wledmusic.engine.core.network.WledMatrix
import com.wledmusic.engine.core.network.WledState
import com.wledmusic.engine.core.render.AnimationDirection
import com.wledmusic.engine.core.render.EffectId
import com.wledmusic.engine.core.render.LedLayout
import com.wledmusic.engine.core.render.PreviewFrame
import com.wledmusic.engine.lamp.LiveEngine
import com.wledmusic.engine.session.LampStatus
import com.wledmusic.engine.session.SessionStateRepository
import com.wledmusic.engine.session.SyncMode
import com.wledmusic.engine.settings.EngineSettings
import com.wledmusic.engine.settings.LampSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private class FakeDeps(saved: LampSettings = LampSettings()) : MainDependencies {
        override val session = SessionStateRepository()
        val store = MutableStateFlow(saved)
        override val savedSettings: Flow<LampSettings> = store
        val engineStore = MutableStateFlow(EngineSettings())
        override val engineSettings: Flow<EngineSettings> = engineStore
        override val layout = MutableStateFlow<LedLayout?>(null)
        override val wifiConnected: Flow<Boolean> = MutableStateFlow(true)
        override val previewSource: StateFlow<StateFlow<PreviewFrame?>?> = MutableStateFlow(null)
        var lampInfo = WledInfo("Lamp", "0.16.0", 256, true, null, matrix = WledMatrix(16, 16))
        var lampResult: WledInfoResult = WledInfoResult.Success(lampInfo)
        var local = true
        var prepareResult: LampStateGuard.PrepareResult = LampStateGuard.PrepareResult.Ready(WledState(true, 128, 0, -1, 0, emptyList()))
        val prepareCalls = ArrayList<Triple<String, Boolean, Boolean>>()
        val cancelled = ArrayList<String>()

        override suspend fun saveSettings(settings: LampSettings) { store.value = settings }
        override suspend fun updateEngine(transform: (EngineSettings) -> EngineSettings) { engineStore.value = transform(engineStore.value) }
        override suspend fun checkLamp(host: String) = MainViewModel.lampStatus(lampResult)
        override suspend fun isLocalAddress(host: String) = local
        override suspend fun prepareLamp(host: String, realtime: Boolean, allowOverrideChange: Boolean): LampStateGuard.PrepareResult {
            prepareCalls += Triple(host, realtime, allowOverrideChange)
            return prepareResult
        }
        override suspend fun cancelPrepared(host: String) { cancelled += host }
        override suspend fun showTestPattern(host: String, layout: LedLayout, direction: AnimationDirection, seconds: Int) = true
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun MainViewModel.canStartNow(deps: FakeDeps) =
        canStart(ui.value, engine.value, deps.session.state.value.lamp, layout.value)

    @Test
    fun restoresSavedSettings() {
        val vm = MainViewModel(FakeDeps(LampSettings("192.168.1.7", 12000, 30)))
        val ui = vm.ui.value
        assertTrue(ui.loaded)
        assertEquals("192.168.1.7", ui.host)
        assertEquals("12000", ui.port)
        assertEquals(30, ui.packetsPerSecond)
    }

    @Test
    fun startDisabledWithoutValidAddress() {
        val deps = FakeDeps()
        val vm = MainViewModel(deps)
        assertFalse(vm.canStartNow(deps))
        vm.onHostChange("300.1.1.1")
        assertTrue(vm.ui.value.hostError)
        assertFalse(vm.canStartNow(deps))
        vm.onHostChange("192.168.1.20")
        assertTrue(vm.canStartNow(deps))
        vm.onPortChange("")
        assertFalse(vm.canStartNow(deps))
    }

    @Test
    fun offlineLampDisablesStart() {
        val deps = FakeDeps()
        val vm = MainViewModel(deps)
        vm.onHostChange("192.168.1.20")
        deps.lampResult = WledInfoResult.Failure(WledInfoResult.Reason.TIMEOUT, null)
        vm.checkLamp()
        assertFalse(vm.canStartNow(deps))
    }

    @Test
    fun audioReactiveStartsImmediatelyAndPersists() = runTest {
        val deps = FakeDeps()
        val vm = MainViewModel(deps)
        vm.onHostChange("192.168.1.20")
        vm.requestStart()
        val request = vm.startRequests.first()
        assertEquals(StartRequest("192.168.1.20", 11988, 50, SyncMode.AUDIO_REACTIVE), request)
        assertEquals("192.168.1.20", deps.store.value.host)
        assertEquals(Triple("192.168.1.20", false, false), deps.prepareCalls.single())
    }

    @Test
    fun audioReactiveStartsEvenIfStateSnapshotFails() = runTest {
        val deps = FakeDeps().apply { prepareResult = LampStateGuard.PrepareResult.Unavailable(WledInfoResult.Reason.TIMEOUT, null) }
        val vm = MainViewModel(deps)
        vm.onHostChange("192.168.1.20")
        vm.requestStart()
        assertEquals(SyncMode.AUDIO_REACTIVE, vm.startRequests.first().mode)
    }

    @Test
    fun rgbEngineNeedsCheckedLampAndLayout() = runTest {
        val deps = FakeDeps()
        deps.engineStore.value = EngineSettings(mode = SyncMode.RGB_ENGINE)
        val vm = MainViewModel(deps)
        vm.onHostChange("192.168.1.20")
        assertFalse(vm.canStartNow(deps)) // лампа не проверена
        vm.checkLamp()
        assertFalse(vm.canStartNow(deps)) // раскладка ещё не вычислена
        deps.layout.value = LedLayout.Matrix(16, 16)
        assertTrue(vm.canStartNow(deps))
        vm.requestStart()
        assertEquals(SyncMode.RGB_ENGINE, vm.startRequests.first().mode)
        assertEquals(Triple("192.168.1.20", true, false), deps.prepareCalls.single())
    }

    @Test
    fun rgbEngineDoesNotStartWhenSnapshotFails() = runTest {
        val deps = FakeDeps()
        deps.engineStore.value = EngineSettings(mode = SyncMode.RGB_ENGINE)
        deps.layout.value = LedLayout.Strip(60)
        val vm = MainViewModel(deps)
        vm.onHostChange("192.168.1.20")
        vm.checkLamp()
        deps.prepareResult = LampStateGuard.PrepareResult.Unavailable(WledInfoResult.Reason.TIMEOUT, null)
        vm.requestStart()
        advanceUntilIdle()
        assertEquals(StartError.LAMP_UNAVAILABLE, vm.ui.value.startError)
    }

    @Test
    fun liveOverrideAsksForConsentThenRetries() = runTest {
        val deps = FakeDeps()
        deps.engineStore.value = EngineSettings(mode = SyncMode.RGB_ENGINE)
        deps.layout.value = LedLayout.Strip(60)
        val vm = MainViewModel(deps)
        vm.onHostChange("192.168.1.20")
        vm.checkLamp()
        deps.prepareResult = LampStateGuard.PrepareResult.NeedsOverrideConsent(1)
        vm.requestStart()
        advanceUntilIdle()
        assertEquals(1, vm.ui.value.overrideConsent)

        deps.prepareResult = LampStateGuard.PrepareResult.Ready(WledState(true, 128, 0, -1, 0, emptyList()))
        vm.confirmOverrideChange()
        assertNull(vm.ui.value.overrideConsent)
        assertEquals(SyncMode.RGB_ENGINE, vm.startRequests.first().mode)
        assertEquals(true, deps.prepareCalls.last().third)
    }

    @Test
    fun deniedPermissionRollsBackPreparedLamp() = runTest {
        val deps = FakeDeps()
        val vm = MainViewModel(deps)
        vm.onHostChange("192.168.1.20")
        vm.requestStart()
        vm.startRequests.first()
        vm.onPermissionDenied()
        assertEquals(listOf("192.168.1.20"), deps.cancelled)
    }

    @Test
    fun publicAddressRequiresConfirmation() = runTest {
        val deps = FakeDeps().apply { local = false }
        val vm = MainViewModel(deps)
        vm.onHostChange("8.8.8.8")
        vm.requestStart()
        advanceUntilIdle()
        assertTrue(vm.ui.value.confirmPublicAddress)
        vm.confirmPublicAddress()
        assertFalse(vm.ui.value.confirmPublicAddress)
        assertEquals("8.8.8.8", vm.startRequests.first().host)
    }

    @Test
    fun lampCheckPublishesStatus() {
        val deps = FakeDeps()
        val vm = MainViewModel(deps)
        vm.onHostChange("192.168.1.20")
        vm.checkLamp()
        assertTrue(deps.session.state.value.lamp is LampStatus.Available)

        deps.lampResult = WledInfoResult.Failure(WledInfoResult.Reason.TIMEOUT, null)
        vm.checkLamp()
        assertEquals(LampStatus.Unavailable(WledInfoResult.Reason.TIMEOUT, null), deps.session.state.value.lamp)
    }

    @Test
    fun modeCannotChangeDuringSession() {
        val deps = FakeDeps()
        val vm = MainViewModel(deps)
        deps.session.onSessionStarted("x:1")
        vm.selectMode(SyncMode.RGB_ENGINE)
        assertEquals(SyncMode.AUDIO_REACTIVE, deps.engineStore.value.mode)
        deps.session.onStoppedByUser()
        vm.selectMode(SyncMode.RGB_ENGINE)
        assertEquals(SyncMode.RGB_ENGINE, deps.engineStore.value.mode)
    }

    @Test
    fun effectParamsAreKeptPerEffect() {
        val deps = FakeDeps()
        val vm = MainViewModel(deps)
        vm.selectEffect(EffectId.RIPPLE)
        vm.updateParams { it.copy(intensity = 11, speed = 22) }
        vm.selectEffect(EffectId.FLOW)
        vm.updateParams { it.copy(intensity = 99) }
        vm.selectEffect(EffectId.RIPPLE)
        val engine = deps.engineStore.value
        assertEquals(11, engine.paramsFor(EffectId.RIPPLE).intensity)
        assertEquals(22, engine.paramsFor(EffectId.RIPPLE).speed)
        assertEquals(99, engine.paramsFor(EffectId.FLOW).intensity)
        assertEquals(listOf(EffectId.RIPPLE, EffectId.FLOW, EffectId.SPECTRUM), engine.recentEffects)
    }

    @Test
    fun invalidDspBoundsAreRejected() {
        val deps = FakeDeps()
        val vm = MainViewModel(deps)
        assertFalse(vm.updateDsp { it.copy(bassRange = 20f..5_000f) })
        assertEquals(DspConfig(), deps.engineStore.value.dsp)
        assertTrue(vm.updateDsp { it.copy(noiseGateDb = -52f) })
        assertEquals(-52f, deps.engineStore.value.dsp.noiseGateDb)
    }

    @Test
    fun layoutOverrideAppliesOnlyToItsLamp() {
        val lamp = LampSettings("192.168.1.20")
        val status = LampStatus.Available(WledInfo("L", "16", 256, false, null, matrix = WledMatrix(16, 16)))
        val override = LedLayout.Matrix(16, 16, rotation = com.wledmusic.engine.core.render.Rotation.R180)
        val engine = EngineSettings(layoutOverride = override, layoutHost = "192.168.1.20")
        assertEquals(override, LiveEngine.layout(lamp, engine, status))
        assertEquals(LedLayout.Matrix(16, 16), LiveEngine.layout(lamp.copy(host = "192.168.1.30"), engine, status))
        assertEquals(AnimationDirection.UP, LiveEngine.direction(EngineSettings(), override))
        assertEquals(AnimationDirection.RIGHT, LiveEngine.direction(EngineSettings(direction = AnimationDirection.UP), LedLayout.Strip(10)))
    }

    @Test
    fun rateIsClamped() {
        val vm = MainViewModel(FakeDeps())
        vm.onRateChange(500)
        assertEquals(60, vm.ui.value.packetsPerSecond)
    }
}
