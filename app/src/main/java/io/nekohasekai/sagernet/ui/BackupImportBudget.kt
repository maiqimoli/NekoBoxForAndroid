package io.nekohasekai.sagernet.ui

internal object BackupImportLimits {
    const val MAX_PROFILE_ENTRIES = 10_000
    const val MAX_GROUP_ENTRIES = 1_000
    const val MAX_RULE_ENTRIES = 10_000
    const val MAX_SETTING_ENTRIES = 5_000
    const val MAX_DECODED_ITEM_BYTES = 1024 * 1024
    const val MAX_DECODED_TOTAL_BYTES = 16 * 1024 * 1024
    const val MAX_ENCODED_ITEM_CHARS = ((MAX_DECODED_ITEM_BYTES + 2) / 3) * 4

    fun maxEntries(section: String): Int = when (section) {
        "profiles" -> MAX_PROFILE_ENTRIES
        "groups" -> MAX_GROUP_ENTRIES
        "rules" -> MAX_RULE_ENTRIES
        "settings" -> MAX_SETTING_ENTRIES
        else -> error("Unknown backup section: $section")
    }
}

internal class BackupImportBudget(
    private val maxDecodedItemBytes: Int = BackupImportLimits.MAX_DECODED_ITEM_BYTES,
    private val maxDecodedTotalBytes: Int = BackupImportLimits.MAX_DECODED_TOTAL_BYTES,
    private val maxEncodedItemChars: Int = BackupImportLimits.MAX_ENCODED_ITEM_CHARS,
) {
    private var decodedBytes = 0L

    init {
        require(maxDecodedItemBytes >= 0)
        require(maxDecodedTotalBytes >= 0)
        require(maxEncodedItemChars >= 0)
    }

    fun requireEntryCount(section: String, count: Int) {
        val limit = BackupImportLimits.maxEntries(section)
        require(count in 0..limit) { "$section contains too many entries: $count (limit $limit)" }
    }

    fun requireEncodedItem(section: String, index: Int, encodedChars: Int) {
        require(encodedChars in 0..maxEncodedItemChars) {
            "$section[$index] is too large before decoding"
        }
    }

    fun recordDecodedItem(section: String, index: Int, decodedSize: Int) {
        require(decodedSize in 0..maxDecodedItemBytes) {
            "$section[$index] is too large after decoding"
        }
        require(decodedBytes + decodedSize <= maxDecodedTotalBytes.toLong()) {
            "Decoded backup data exceeds $maxDecodedTotalBytes bytes"
        }
        decodedBytes += decodedSize
    }
}
