package io.nekohasekai.sagernet.ui

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerImportSessionTest {

    @Test
    fun tryStart_allowsOnlyOneEntry() {
        val session = ScannerImportSession()

        assertTrue(session.tryStart())
        assertFalse(session.tryStart())
    }

    @Test
    fun runBatch_waitsForEveryImport() = runBlocking {
        val session = ScannerImportSession()
        val imported = mutableListOf<Int>()

        session.runBatch(listOf(1, 2, 3)) {
            yield()
            imported += it
        }

        assertEquals(listOf(1, 2, 3), imported)
    }
}
