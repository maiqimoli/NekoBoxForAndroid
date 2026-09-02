package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConnectionTestCoordinatorTest {

    @Test
    fun concurrentTryBeginAllowsOnlyOneSession() {
        val coordinator = ConnectionTestCoordinator()
        val ready = CountDownLatch(16)
        val start = CountDownLatch(1)
        val done = CountDownLatch(16)
        val tokens = Collections.synchronizedList(mutableListOf<ConnectionTestToken>())

        repeat(16) {
            Thread {
                ready.countDown()
                start.await()
                coordinator.tryBegin()?.let(tokens::add)
                done.countDown()
            }.start()
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, tokens.size)
    }

    @Test
    fun startThenDetachLeavesNoVisibleTestingProfile() {
        val coordinator = ConnectionTestCoordinator()
        val token = requireNotNull(coordinator.tryBegin())

        assertEquals(
            ConnectionTestStartDecision.ACCEPTED_VISIBLE,
            coordinator.profileStarted(token, 11L),
        )
        assertTrue(coordinator.isTesting(11L))
        assertEquals(setOf(11L), coordinator.detachUi(token))
        assertFalse(coordinator.isTesting(11L))
    }

    @Test
    fun startAfterDetachContinuesWithoutVisibleTestingState() {
        val coordinator = ConnectionTestCoordinator()
        val token = requireNotNull(coordinator.tryBegin())

        assertTrue(coordinator.detachUi(token).isEmpty())
        assertEquals(
            ConnectionTestStartDecision.ACCEPTED_HIDDEN,
            coordinator.profileStarted(token, 12L),
        )
        assertFalse(coordinator.isTesting(12L))
    }

    @Test
    fun beginFinishRejectsLateStartsAndCompletions() {
        val coordinator = ConnectionTestCoordinator(nowSeconds = { 100L })
        val token = requireNotNull(coordinator.tryBegin())
        assertEquals(
            ConnectionTestStartDecision.ACCEPTED_VISIBLE,
            coordinator.profileStarted(token, 13L),
        )

        assertEquals(setOf(13L), coordinator.beginFinish(token))
        assertEquals(
            ConnectionTestStartDecision.REJECTED,
            coordinator.profileStarted(token, 14L),
        )
        val completion = coordinator.profileCompleted(token, 13L)
        assertFalse(completion.accepted)
        assertNull(completion.acceptedAtSeconds)
        assertFalse(coordinator.isTesting(13L))
    }

    @Test
    fun completionBeforeFinishRetainsItsAcceptedTimestamp() {
        val coordinator = ConnectionTestCoordinator(nowSeconds = { 1_234L })
        val token = requireNotNull(coordinator.tryBegin())
        coordinator.profileStarted(token, 15L)

        val completion = coordinator.profileCompleted(token, 15L)
        assertTrue(completion.accepted)
        assertEquals(1_234L, completion.acceptedAtSeconds)
        assertTrue(completion.testingStateChanged)
        assertTrue(requireNotNull(coordinator.beginFinish(token)).isEmpty())
    }

    @Test
    fun staleEndFinishDoesNotReleaseANewerSession() {
        val coordinator = ConnectionTestCoordinator()
        val first = requireNotNull(coordinator.tryBegin())
        requireNotNull(coordinator.beginFinish(first))
        assertTrue(coordinator.endFinish(first))

        val second = requireNotNull(coordinator.tryBegin())
        assertFalse(coordinator.endFinish(first))
        assertTrue(coordinator.isRunning())
        assertEquals(
            ConnectionTestStartDecision.ACCEPTED_VISIBLE,
            coordinator.profileStarted(second, 16L),
        )
    }

    @Test
    fun racingStartAndDetachNeverLeavesAVisibleTestingProfile() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(250) { iteration ->
                val coordinator = ConnectionTestCoordinator()
                val token = requireNotNull(coordinator.tryBegin())
                val start = CountDownLatch(1)
                val done = CountDownLatch(2)
                val profileId = iteration + 1L

                executor.execute {
                    start.await()
                    coordinator.profileStarted(token, profileId)
                    done.countDown()
                }
                executor.execute {
                    start.await()
                    coordinator.detachUi(token)
                    done.countDown()
                }

                start.countDown()
                assertTrue(done.await(5, TimeUnit.SECONDS))
                assertFalse(coordinator.isTesting(profileId))
                requireNotNull(coordinator.beginFinish(token))
                assertTrue(coordinator.endFinish(token))
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun persistencePublishesOnlyRowsUpdatedExactlyOnce() {
        val results = listOf(
            completedResult(profileId = 21L, timestampSeconds = 201L),
            completedResult(profileId = 22L, timestampSeconds = 202L),
            completedResult(profileId = 23L, timestampSeconds = 203L),
        )
        val failures = mutableListOf<Long>()

        val persisted = persistConnectionTestResults(
            results = results,
            write = { result ->
                when (result.profileId) {
                    21L -> 1
                    22L -> 0
                    else -> error("write failed")
                }
            },
            onFailure = { result, _ -> failures += result.profileId },
        )

        assertEquals(mapOf(21L to results[0].record), persisted)
        assertEquals(listOf(23L), failures)
    }

    private fun completedResult(
        profileId: Long,
        timestampSeconds: Long,
    ) = CompletedConnectionTest(
        profileId = profileId,
        status = 1,
        ping = 42,
        error = null,
        record = ProfileTestRecord(timestampSeconds, ProfileTestMethod.URL),
    )
}
