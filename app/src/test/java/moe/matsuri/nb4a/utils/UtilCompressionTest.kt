package moe.matsuri.nb4a.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.util.zip.Deflater

class UtilCompressionTest {

    @Test
    fun zlibRoundTripsSmallPayloads() {
        val payloads = listOf(
            byteArrayOf(),
            byteArrayOf(0),
            byteArrayOf(0, 1),
            "small universal link payload".toByteArray(),
        )

        payloads.forEach { payload ->
            val compressed = Util.zlibCompress(payload, Deflater.BEST_COMPRESSION)
            assertArrayEquals(payload, Util.zlibDecompress(compressed))
        }
    }
}
