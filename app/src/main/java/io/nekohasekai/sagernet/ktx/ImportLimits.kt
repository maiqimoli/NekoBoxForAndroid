package io.nekohasekai.sagernet.ktx

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal const val MAX_IMPORTED_CONFIG_BYTES = 16 * 1024 * 1024
internal const val MAX_IMPORTED_ARCHIVE_BYTES = 64 * 1024 * 1024
internal const val MAX_IMPORTED_ARCHIVE_ENTRIES = 256

// Subscription parsing has its own post-decode limits. The input byte limit alone does not
// bound the number of objects produced by YAML aliases, JSON arrays, or many very short links.
internal const val MAX_SUBSCRIPTION_PROFILES = 10_000
internal const val MAX_SUBSCRIPTION_FIELD_BYTES = 64 * 1024
internal const val MAX_SUBSCRIPTION_DECODED_BYTES = MAX_IMPORTED_CONFIG_BYTES
internal const val MAX_SUBSCRIPTION_DECODED_NODES = 200_000
internal const val MAX_SUBSCRIPTION_NESTING_DEPTH = 64

@Throws(IOException::class)
internal fun InputStream.readBytesLimited(maxBytes: Int): ByteArray {
    require(maxBytes >= 0) { "maxBytes must not be negative" }
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val readLimit = minOf(buffer.size.toLong(), maxBytes.toLong() - total + 1L).toInt()
        val read = read(buffer, 0, readLimit)
        if (read < 0) break
        if (read == 0) {
            val next = read()
            if (next < 0) break
            total += 1
            if (total > maxBytes) throw IOException("Input exceeds $maxBytes bytes")
            output.write(next)
            continue
        }
        total += read
        if (total > maxBytes) throw IOException("Input exceeds $maxBytes bytes")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

@Throws(IOException::class)
internal fun InputStream.readTextLimited(maxBytes: Int = MAX_IMPORTED_CONFIG_BYTES): String {
    return readBytesLimited(maxBytes).toString(Charsets.UTF_8)
}

@Throws(IOException::class)
internal fun String.requireUtf8SizeAtMost(maxBytes: Int): String {
    if (toByteArray(Charsets.UTF_8).size > maxBytes) {
        throw IOException("Input exceeds $maxBytes bytes")
    }
    return this
}
