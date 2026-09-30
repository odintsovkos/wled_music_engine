package com.wledmusic.engine.core.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LampStateGuardTest {
    private class FakeLamp(var lor: Int = 0) : LampApi {
        var on = true
        var bri = 128
        var reachable = true
        var failPosts = 0
        val posts = ArrayList<String>()

        override fun state(host: String): WledResult<WledState> =
            if (!reachable) WledResult.Failure(WledInfoResult.Reason.TIMEOUT, "timeout")
            else WledResult.Success(WledState(on, bri, lor, -1, 0, emptyList()))

        override fun postState(host: String, body: String): WledResult<Unit> {
            if (!reachable) return WledResult.Failure(WledInfoResult.Reason.NETWORK, "unreachable")
            if (failPosts > 0) { failPosts--; return WledResult.Failure(WledInfoResult.Reason.TIMEOUT, "slow") }
            posts += body
            Regex(""""lor":(\d+)""").find(body)?.let { lor = it.groupValues[1].toInt() }
            return WledResult.Success(Unit)
        }
    }

    private class MemoryStore : LampJournalStore {
        var journal: LampJournal? = null
        override suspend fun load() = journal
        override suspend fun save(journal: LampJournal?) { this.journal = journal }
    }

    private fun guard(lamp: FakeLamp, store: MemoryStore) = LampStateGuard(lamp, store, sleep = {})

    @Test
    fun unreachableLampBlocksStart() = runTest {
        val lamp = FakeLamp().apply { reachable = false }
        val r = guard(lamp, MemoryStore()).prepare("wled", realtime = true, allowOverrideChange = false)
        assertTrue(r is LampStateGuard.PrepareResult.Unavailable)
        assertTrue(lamp.posts.isEmpty())
    }

    @Test
    fun liveOverrideNeedsConsentAndIsNotChangedWithoutIt() = runTest {
        val lamp = FakeLamp(lor = 1)
        val store = MemoryStore()
        val r = guard(lamp, store).prepare("wled", realtime = true, allowOverrideChange = false)
        assertEquals(LampStateGuard.PrepareResult.NeedsOverrideConsent(1), r)
        assertEquals(1, lamp.lor)
        assertNull(store.journal)
    }

    @Test
    fun consentJournalsBeforeChangingAndRestoresAfter() = runTest {
        val lamp = FakeLamp(lor = 2)
        val store = MemoryStore()
        val g = guard(lamp, store)
        assertTrue(g.prepare("wled", realtime = true, allowOverrideChange = true) is LampStateGuard.PrepareResult.Ready)
        assertEquals(0, lamp.lor)
        assertEquals("wled|lor:2:0", store.journal!!.encode())

        val result = g.finish("wled", exitRealtime = true) as LampStateGuard.RestoreResult.Restored
        assertEquals(listOf(LampJournal.Field.LIVE_OVERRIDE), result.reverted)
        assertEquals(2, lamp.lor)
        assertEquals("""{"live":false}""", lamp.posts[1])
        assertNull(store.journal)
    }

    @Test
    fun userChangeDuringSessionIsKept() = runTest {
        val lamp = FakeLamp(lor = 1)
        val store = MemoryStore()
        val g = guard(lamp, store)
        g.prepare("wled", realtime = true, allowOverrideChange = true)
        lamp.lor = 2 // пользователь сам включил override в WLED
        val result = g.finish("wled", exitRealtime = true) as LampStateGuard.RestoreResult.Restored
        assertTrue(result.reverted.isEmpty())
        assertEquals(listOf(LampJournal.Field.LIVE_OVERRIDE), result.keptUserChanges)
        assertEquals(2, lamp.lor)
    }

    @Test
    fun plainRealtimeSessionOnlyExitsLive() = runTest {
        val lamp = FakeLamp()
        val store = MemoryStore()
        val g = guard(lamp, store)
        g.prepare("wled", realtime = true, allowOverrideChange = false)
        assertNull(store.journal)
        assertTrue(g.finish("wled", exitRealtime = true) is LampStateGuard.RestoreResult.Restored)
        assertEquals(listOf("""{"live":false}"""), lamp.posts)
    }

    @Test
    fun nativeSessionTouchesNothing() = runTest {
        val lamp = FakeLamp(lor = 1)
        val g = guard(lamp, MemoryStore())
        assertTrue(g.prepare("wled", realtime = false, allowOverrideChange = false) is LampStateGuard.PrepareResult.Ready)
        assertEquals(LampStateGuard.RestoreResult.NothingToRestore, g.finish("wled", exitRealtime = false))
        assertTrue(lamp.posts.isEmpty())
    }

    @Test
    fun transientErrorsAreRetried() = runTest {
        val lamp = FakeLamp(lor = 1)
        val store = MemoryStore()
        val g = guard(lamp, store)
        g.prepare("wled", realtime = true, allowOverrideChange = true)
        lamp.failPosts = 2
        assertTrue(g.finish("wled", exitRealtime = true) is LampStateGuard.RestoreResult.Restored)
        assertEquals(1, lamp.lor)
    }

    @Test
    fun unreachableAtStopDefersAndPendingIsRestoredLater() = runTest {
        val lamp = FakeLamp(lor = 1)
        val store = MemoryStore()
        val g = guard(lamp, store)
        g.prepare("wled", realtime = true, allowOverrideChange = true)
        lamp.reachable = false
        assertTrue(g.finish("wled", exitRealtime = true) is LampStateGuard.RestoreResult.Deferred)
        assertEquals("wled|lor:1:0", store.journal!!.encode())

        // «Следующий запуск»: новый guard поверх того же хранилища, live:false не отправляется.
        lamp.reachable = true
        val restored = guard(lamp, store).restorePending() as LampStateGuard.RestoreResult.Restored
        assertEquals(listOf(LampJournal.Field.LIVE_OVERRIDE), restored.reverted)
        assertEquals(listOf("""{"lor":1}"""), lamp.posts.drop(1))
        assertNull(store.journal)
    }

    @Test
    fun pendingJournalIsRestoredBeforeNewSession() = runTest {
        val lamp = FakeLamp(lor = 0)
        val store = MemoryStore().apply { journal = LampJournal("wled", listOf(LampJournal.Entry(LampJournal.Field.LIVE_OVERRIDE, 2, 0))) }
        val r = guard(lamp, store).prepare("wled", realtime = true, allowOverrideChange = false)
        assertEquals(LampStateGuard.PrepareResult.NeedsOverrideConsent(2), r)
    }

    @Test
    fun journalCodecRoundTrip() {
        val j = LampJournal("192.168.0.124", listOf(
            LampJournal.Entry(LampJournal.Field.LIVE_OVERRIDE, 1, 0),
            LampJournal.Entry(LampJournal.Field.ON, 0, 1),
        ))
        assertEquals(j, LampJournal.decode(j.encode()))
        assertNull(LampJournal.decode(""))
        assertNull(LampJournal.decode("host|bogus:1:0"))
        assertEquals("""{"on":true}""", LampJournal.Field.ON.json(1))
    }
}
