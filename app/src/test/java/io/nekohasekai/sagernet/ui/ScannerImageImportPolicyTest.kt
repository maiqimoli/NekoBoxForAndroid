package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScannerImageImportPolicyTest {

    @Test
    fun acceptsSelectionLimitAndRejectsExcess() {
        ScannerImageImportPolicy.requireSelectionCount(
            ScannerImageImportPolicy.MAX_SELECTED_IMAGES
        )

        assertThrows(IllegalArgumentException::class.java) {
            ScannerImageImportPolicy.requireSelectionCount(
                ScannerImageImportPolicy.MAX_SELECTED_IMAGES + 1
            )
        }
    }

    @Test
    fun rejectsInvalidAndOversizedDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            ScannerImageImportPolicy.createDecodePlan(0, 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScannerImageImportPolicy.createDecodePlan(8_001, 5_000)
        }
    }

    @Test
    fun keepsImagesWithinDecodedEdge() {
        assertEquals(
            ScannerImageDecodePlan(1, 2048, 1024),
            ScannerImageImportPolicy.createDecodePlan(2048, 1024),
        )
        assertEquals(
            ScannerImageDecodePlan(2, 1500, 1000),
            ScannerImageImportPolicy.createDecodePlan(3000, 2000),
        )
        assertEquals(
            ScannerImageDecodePlan(4, 2000, 1250),
            ScannerImageImportPolicy.createDecodePlan(8000, 5000),
        )
    }
}
