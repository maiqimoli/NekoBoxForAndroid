package io.nekohasekai.sagernet.ktx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class ImportLimitsTest {

    @Test
    fun readsInputAtLimit() {
        val input = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(input, ByteArrayInputStream(input).readBytesLimited(4))
    }

    @Test
    fun rejectsInputBeyondLimit() {
        assertThrows(IOException::class.java) {
            ByteArrayInputStream(ByteArray(5)).readBytesLimited(4)
        }
    }

    @Test
    fun toleratesAZeroLengthBulkRead() {
        val input = ZeroFirstBulkReadInputStream(byteArrayOf(1, 2, 3, 4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), input.readBytesLimited(4))
    }

    @Test
    fun exportedTextUsesTheSameUtf8LimitAsImportedText() {
        val boundary = "\u00e9\u00e9"
        assertEquals(boundary, boundary.requireUtf8SizeAtMost(4))
        assertThrows(IOException::class.java) {
            boundary.requireUtf8SizeAtMost(3)
        }
    }

    private class ZeroFirstBulkReadInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        private var returnZero = true

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (returnZero) {
                returnZero = false
                return 0
            }
            return super.read(buffer, offset, length)
        }
    }
}
