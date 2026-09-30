package com.wledmusic.engine.ui

import com.wledmusic.engine.core.network.WledInfo
import com.wledmusic.engine.core.network.WledInfoResult
import com.wledmusic.engine.session.LampStatus
import com.wledmusic.engine.session.SessionStateRepository
import com.wledmusic.engine.settings.LampSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private class FakeDeps(saved: LampSettings = LampSettings()) : MainDependencies {
        override val session = SessionStateRepository()
        val store = MutableStateFlow(saved)
        override val savedSettings: Flow<LampSettings> = store
        var lampResult: WledInfoResult = WledInfoResult.Success(WledInfo("Lamp", "0.16.0", 60, true, null))
        var local = true
        override suspend fun saveSettings(settings: LampSettings) { store.value = settings }
        override suspend fun checkLamp(host: String) = lampResult
        override suspend fun isLocalAddress(host: String) = local
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

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
        val vm = MainViewModel(FakeDeps())
        assertFalse(vm.ui.value.canStart)
        vm.onHostChange("300.1.1.1")
        assertTrue(vm.ui.value.hostError)
        assertFalse(vm.ui.value.canStart)
        vm.onHostChange("192.168.1.20")
        assertTrue(vm.ui.value.canStart)
        vm.onPortChange("")
        assertFalse(vm.ui.value.canStart)
    }

    @Test
    fun localAddressStartsImmediatelyAndPersists() = runTest {
        val deps = FakeDeps()
        val vm = MainViewModel(deps)
        vm.onHostChange("192.168.1.20")
        vm.requestStart()
        val request = vm.startRequests.first()
        assertEquals(StartRequest("192.168.1.20", 11988, 50), request)
        assertEquals("192.168.1.20", deps.store.value.host)
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
    fun rateIsClamped() {
        val vm = MainViewModel(FakeDeps())
        vm.onRateChange(500)
        assertEquals(60, vm.ui.value.packetsPerSecond)
    }
}
