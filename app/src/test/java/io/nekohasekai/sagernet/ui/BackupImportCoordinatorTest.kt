package io.nekohasekai.sagernet.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupImportCoordinatorTest {

    @Test
    fun restoresPrimaryWhenSecondaryCommitFails() {
        var primary = "original"
        var secondary = "original"
        val failure = IllegalStateException("secondary failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                commitCompensatingImport(
                    hasPrimaryChanges = true,
                    hasSecondaryChanges = true,
                    capturePrimary = { primary },
                    applyPrimary = { primary = "imported" },
                    restorePrimary = { primary = it },
                    applySecondary = {
                        secondary = "partially changed"
                        secondary = "original"
                        throw failure
                    },
                )
            }
        }

        assertSame(failure, thrown)
        assertEquals("original", primary)
        assertEquals("original", secondary)
    }

    @Test
    fun reportsRestoreFailureWithoutHidingImportFailure() {
        val importFailure = IllegalStateException("secondary failed")
        val restoreFailure = IllegalArgumentException("restore failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                commitCompensatingImport(
                    hasPrimaryChanges = true,
                    hasSecondaryChanges = true,
                    capturePrimary = { Unit },
                    applyPrimary = {},
                    restorePrimary = { throw restoreFailure },
                    applySecondary = { throw importFailure },
                )
            }
        }

        assertSame(importFailure, thrown)
        val suppressed = thrown.suppressed.single()
        assertTrue(suppressed is IllegalArgumentException)
        assertEquals(restoreFailure.message, suppressed.message)
    }

    @Test
    fun restoresPrimaryInNonCancellableContextWhenImportIsCancelled() = runBlocking {
        withTimeout(5_000) {
            var primary = "original"
            var restored = false
            val secondaryStarted = CompletableDeferred<Unit>()

            val importJob = async {
                commitCompensatingImport(
                    hasPrimaryChanges = true,
                    hasSecondaryChanges = true,
                    capturePrimary = { primary },
                    applyPrimary = { primary = "imported" },
                    restorePrimary = {
                        delay(1)
                        primary = it
                        restored = true
                    },
                    applySecondary = {
                        secondaryStarted.complete(Unit)
                        awaitCancellation()
                    },
                )
            }

            secondaryStarted.await()
            val cancellation = CancellationException("secondary import cancelled")
            importJob.cancel(cancellation)
            val thrown = try {
                importJob.await()
                throw AssertionError("Expected cancellation")
            } catch (error: CancellationException) {
                error
            }

            assertEquals(cancellation.message, thrown.message)
            assertTrue(importJob.isCancelled)
            assertTrue(restored)
            assertEquals("original", primary)
        }
    }
}
