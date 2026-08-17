package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.bg.proto.medianLatency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileUiStateTest {

    @Test
    fun decodeIdsRemovesInvalidAndDuplicateValues() {
        assertEquals(listOf(42L, 7L), ProfileUiState.decodeIds("42,invalid,7,42,0,-1"))
    }

    @Test
    fun normalizeRecentIdsKeepsOrderAndCapsHistory() {
        val values = listOf(0L, -1L, 5L, 5L) + (1L..25L)
        assertEquals(
            listOf(5L, 1L, 2L, 3L, 4L) + (6L..20L),
            ProfileUiState.normalizeRecentIds(values),
        )
    }

    @Test
    fun unknownFilterFallsBackToAll() {
        assertEquals(ProfileFilter.ALL, ProfileFilter.fromKey("not-a-filter"))
        assertTrue(ProfileFilter.FAVORITES.key == "favorites")
    }

    @Test
    fun decodeTestTimesDropsMalformedEntries() {
        assertEquals(
            mapOf(42L to 1000L, 7L to 2000L),
            ProfileUiState.decodeTestTimes("42:1000,bad,7:2000,0:3000,8:nope")
        )
    }

    @Test
    fun decodeTestRecordsSupportsLegacyAndTypedEntries() {
        assertEquals(
            mapOf(
                42L to ProfileTestRecord(1000L, ProfileTestMethod.UNKNOWN),
                7L to ProfileTestRecord(2000L, ProfileTestMethod.URL),
                8L to ProfileTestRecord(3000L, ProfileTestMethod.TCP),
            ),
            ProfileUiState.decodeTestRecords("42:1000,7:2000:url,8:3000:tcp")
        )
    }

    @Test
    fun workerCountCapsChainsMoreAggressively() {
        assertEquals(8, connectionTestWorkerCount(20, 50, containsChain = false))
        assertEquals(2, connectionTestWorkerCount(20, 50, containsChain = true))
        assertEquals(1, connectionTestWorkerCount(1, 5, containsChain = false))
        assertEquals(0, connectionTestWorkerCount(0, 5, containsChain = false))
    }

    @Test
    fun latencyUsesMedianSample() {
        assertEquals(80, medianLatency(listOf(80, 450, 60)))
    }
}
