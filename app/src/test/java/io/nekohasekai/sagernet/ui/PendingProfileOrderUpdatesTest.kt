package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyOrderUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingProfileOrderUpdatesTest {

    @Test
    fun acknowledgementOnlyClearsTheCommittedVersion() {
        val updates = PendingProfileOrderUpdates()
        val first = ProxyOrderUpdate(profileId = 7L, userOrder = 10L)

        updates.record(1L, listOf(first))
        val firstSnapshot = requireNotNull(updates.snapshot())

        updates.record(2L, listOf(first.copy(userOrder = 20L)))
        updates.acknowledge(firstSnapshot)

        assertEquals(20L, updates.snapshot()!!.updates.single().userOrder)

        val latestSnapshot = requireNotNull(updates.snapshot())
        updates.acknowledge(latestSnapshot)
        assertTrue(updates.isEmpty())
    }

    @Test
    fun sameValueInANewerGenerationIsNotAcknowledgedAsTheOldWrite() {
        val updates = PendingProfileOrderUpdates()
        val order = ProxyOrderUpdate(profileId = 9L, userOrder = 1L)

        updates.record(10L, listOf(order))
        val oldSnapshot = requireNotNull(updates.snapshot())
        updates.record(11L, listOf(order))
        updates.acknowledge(oldSnapshot)

        assertFalse(updates.isEmpty())
        assertEquals(11L, updates.snapshot()!!.entries.single().generation)
    }

    @Test
    fun snapshotContainsOnlyImmutableOrderValues() {
        val updates = PendingProfileOrderUpdates()
        val submitted = mutableListOf(ProxyOrderUpdate(profileId = 3L, userOrder = 4L))

        updates.record(21L, submitted)
        submitted[0] = submitted[0].copy(userOrder = 99L)

        val snapshot = requireNotNull(updates.snapshot())
        assertEquals(listOf(ProxyOrderUpdate(3L, 4L)), snapshot.updates)
        assertEquals(mapOf(3L to 4L), updates.currentOrders())
    }

    @Test(expected = IllegalArgumentException::class)
    fun generationsMustIncreaseAcrossAdapterLifetimes() {
        val updates = PendingProfileOrderUpdates()
        updates.record(5L, listOf(ProxyOrderUpdate(1L, 1L)))
        updates.record(5L, listOf(ProxyOrderUpdate(1L, 2L)))
    }

    @Test
    fun capturingAReadDoesNotInvalidateTheWriterSession() {
        val state = ProfileOrderGroupState(groupId = 12L)
        val writer = state.attachWriter()

        val read = state.captureRead()
        assertTrue(state.isCurrent(read))

        val submission = state.submit(
            writer.session,
            listOf(ProxyOrderUpdate(profileId = 1L, userOrder = 2L)),
        )

        assertTrue(submission.accepted)
        assertTrue(submission.startWriter)
    }

    @Test
    fun attachingASecondWriterInvalidatesTheFirstWriterSession() {
        val state = ProfileOrderGroupState(groupId = 13L)
        val first = state.attachWriter()
        val second = state.attachWriter()
        val update = listOf(ProxyOrderUpdate(profileId = 1L, userOrder = 3L))

        val staleSubmission = state.submit(first.session, update)
        val currentSubmission = state.submit(second.session, update)

        assertFalse(staleSubmission.accepted)
        assertFalse(staleSubmission.startWriter)
        assertTrue(currentSubmission.accepted)
        assertTrue(currentSubmission.startWriter)
        assertEquals(mapOf(1L to 3L), state.currentOrders())
    }

    @Test
    fun acknowledgingAnInflightSnapshotKeepsANewerSubmission() {
        val state = ProfileOrderGroupState(groupId = 14L)
        val writer = state.attachWriter()
        state.submit(
            writer.session,
            listOf(ProxyOrderUpdate(profileId = 7L, userOrder = 10L)),
        )
        val inflight = requireNotNull(state.takeWriteSnapshot())

        val newerSubmission = state.submit(
            writer.session,
            listOf(ProxyOrderUpdate(profileId = 7L, userOrder = 20L)),
        )
        state.acknowledge(inflight)

        assertTrue(newerSubmission.accepted)
        assertFalse(newerSubmission.startWriter)
        assertEquals(mapOf(7L to 20L), state.currentOrders())
    }

    @Test
    fun writerAttachDuringFailuresDoesNotStartACompetingWriter() {
        val state = ProfileOrderGroupState(groupId = 15L)
        val first = state.attachWriter()
        state.submit(
            first.session,
            listOf(ProxyOrderUpdate(profileId = 8L, userOrder = 30L)),
        )
        val failingSnapshot = requireNotNull(state.takeWriteSnapshot())

        val replacement = state.attachWriter()

        assertFalse(replacement.startWriter)
        assertTrue(state.continueAfterRetryRound())
        assertTrue(state.isWriterRunningForTest())
    }

    @Test
    fun exhaustedWriterKeepsRetryOwnershipWithoutAnotherAttachment() {
        val state = ProfileOrderGroupState(groupId = 16L)
        val first = state.attachWriter()
        state.submit(
            first.session,
            listOf(ProxyOrderUpdate(profileId = 9L, userOrder = 40L)),
        )
        val failingSnapshot = requireNotNull(state.takeWriteSnapshot())

        assertTrue(state.continueAfterRetryRound())
        assertTrue(state.isWriterRunningForTest())
        assertEquals(mapOf(9L to 40L), state.currentOrders())

        state.acknowledge(failingSnapshot)
        assertNull(state.takeWriteSnapshot())
        assertFalse(state.isWriterRunningForTest())
    }

    @Test
    fun takingAnEmptySnapshotStopsTheWriter() {
        val state = ProfileOrderGroupState(groupId = 17L)

        assertNull(state.takeWriteSnapshot())
        assertFalse(state.isWriterRunningForTest())
    }

    @Test
    fun visibleDragRetainsHiddenRowsInTheirSourceSlots() {
        assertEquals(
            listOf(4L, 2L, 1L, 3L),
            mergeVisibleProfileOrder(
                sourceIds = listOf(1L, 2L, 3L, 4L),
                visibleIds = listOf(4L, 1L, 3L),
            ),
        )
    }

    @Test
    fun profileUpdateKeepsTheOrderAlreadyShownByTheAdapter() {
        assertEquals(25L, resolveUpdatedProfileOrder(incomingOrder = 4L, currentOrder = 25L))
        assertEquals(4L, resolveUpdatedProfileOrder(incomingOrder = 4L, currentOrder = null))
    }

    @Test
    fun duplicateOrdersUsePreferredRankThenIdForStableSorting() {
        val profiles = listOf(4L, 2L, 1L, 3L).map { id ->
            ProxyEntity(id = id, userOrder = 10L)
        }

        val sorted = sortProfilesForGroup(
            profiles = profiles,
            groupOrder = GroupOrder.ORIGIN,
            preferredIds = listOf(3L, 2L),
        )

        assertEquals(listOf(3L, 2L, 1L, 4L), sorted.map { it.id })
        assertEquals(
            listOf(1L, 2L, 3L, 4L),
            sortProfilesForGroup(profiles, GroupOrder.ORIGIN).map { it.id },
        )
    }
}
