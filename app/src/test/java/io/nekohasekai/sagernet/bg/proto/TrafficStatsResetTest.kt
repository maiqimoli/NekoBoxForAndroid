package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.TrafficData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficStatsResetTest {

    @Test
    fun profileIdsArePositiveDistinctAndKeepInputOrder() {
        val normalized = normalizeTrafficProfileIds(
            longArrayOf(7L, 0L, -3L, 2L, 7L, 2L, 11L),
        )

        assertArrayEquals(longArrayOf(7L, 2L, 11L), normalized)
    }

    @Test
    fun accumulatorResetClearsTotalsBaselinesAndRates() {
        val accumulator = TrafficUpdater.TrafficLooperData(
            tag = "proxy",
            tx = 101L,
            rx = 202L,
            txBase = 11L,
            rxBase = 22L,
            txRate = 3L,
            rxRate = 4L,
            lastUpdate = 5L,
            ignore = false,
        )

        resetTrafficAccumulator(accumulator, nowMillis = 9_999L)

        assertEquals(0L, accumulator.tx)
        assertEquals(0L, accumulator.rx)
        assertEquals(0L, accumulator.txBase)
        assertEquals(0L, accumulator.rxBase)
        assertEquals(0L, accumulator.txRate)
        assertEquals(0L, accumulator.rxRate)
        assertEquals(9_999L, accumulator.lastUpdate)
        assertEquals("proxy", accumulator.tag)
        assertFalse(accumulator.ignore)
    }

    @Test
    fun persistenceStateReturnsOnlyChangedPositiveProfiles() {
        val state = TrafficPersistenceState()
        state.reset(
            listOf(
                traffic(id = -1L, rx = 10L, tx = 20L),
                traffic(id = 1L, rx = 100L, tx = 200L),
                traffic(id = 2L, rx = 300L, tx = 400L),
            ),
        )

        val dirty = state.dirtyTraffic(
            listOf(
                traffic(id = -1L, rx = 11L, tx = 21L),
                traffic(id = 1L, rx = 100L, tx = 200L),
                traffic(id = 2L, rx = 301L, tx = 400L),
                traffic(id = 3L, rx = 1L, tx = 2L),
            ),
        )

        assertEquals(listOf(2L, 3L), dirty.map(TrafficData::id))
        assertEquals(301L, dirty.first().rx)
        assertEquals(2L, dirty.last().tx)
    }

    @Test
    fun failedWriteDoesNotAdvancePersistenceBaseline() = runBlocking {
        val state = TrafficPersistenceState()
        state.reset(listOf(traffic(id = 1L, rx = 10L, tx = 20L)))
        val changed = listOf(traffic(id = 1L, rx = 11L, tx = 22L))

        val firstAttempt = state.dirtyTraffic(changed)
        try {
            state.persistDirty(changed) { throw ExpectedWriteFailure }
        } catch (error: IllegalStateException) {
            assertEquals(ExpectedWriteFailure, error)
        }
        val retryAttempt = state.dirtyTraffic(changed)

        assertEquals(firstAttempt, retryAttempt)
        state.persistDirty(retryAttempt) { }
        assertTrue(state.dirtyTraffic(changed).isEmpty())
    }

    @Test
    fun partialSelectorCommitLeavesOtherProfilesDirty() = runBlocking {
        val state = TrafficPersistenceState()
        state.reset(
            listOf(
                traffic(id = 1L, rx = 10L, tx = 20L),
                traffic(id = 2L, rx = 30L, tx = 40L),
            ),
        )
        val current = listOf(
            traffic(id = 1L, rx = 11L, tx = 21L),
            traffic(id = 2L, rx = 31L, tx = 41L),
        )

        state.persistDirty(listOf(current.first())) { }

        assertEquals(listOf(2L), state.dirtyTraffic(current).map(TrafficData::id))
    }

    @Test
    fun successfulClearResetsOnlyRequestedBaselines() {
        val state = TrafficPersistenceState()
        state.reset(
            listOf(
                traffic(id = 1L, rx = 10L, tx = 20L),
                traffic(id = 2L, rx = 30L, tx = 40L),
            ),
        )
        state.markCleared(longArrayOf(1L))

        val dirty = state.dirtyTraffic(
            listOf(
                traffic(id = 1L, rx = 0L, tx = 0L),
                traffic(id = 2L, rx = 31L, tx = 40L),
            ),
        )

        assertEquals(listOf(2L), dirty.map(TrafficData::id))
    }

    private fun traffic(id: Long, rx: Long, tx: Long) = TrafficData(
        id = id,
        rx = rx,
        tx = tx,
    )

    private companion object {
        val ExpectedWriteFailure = IllegalStateException("expected write failure")
    }
}
