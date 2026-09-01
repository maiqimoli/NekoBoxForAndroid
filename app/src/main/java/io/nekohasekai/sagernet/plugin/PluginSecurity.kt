package io.nekohasekai.sagernet.plugin

import java.util.Locale

internal object PluginSecurity {

    enum class TrustLevel(val canExecute: Boolean, val canExecuteAsRoot: Boolean) {
        NONE(false, false),
        USER(true, false),
        ROOT(true, true),
    }

    private val packageNamePattern = Regex("[A-Za-z0-9_.]+")
    private val digestPattern = Regex("[0-9a-f]{64}")

    fun isTrusted(
        records: Set<String>,
        packageName: String,
        signerDigests: Set<String>,
    ): Boolean {
        if (signerDigests.isEmpty()) return false
        val normalizedDigests = normalizeDigests(signerDigests) ?: return false
        return records.any { record ->
            parse(record)?.let { (recordPackage, recordDigests) ->
                recordPackage == packageName && recordDigests == normalizedDigests
            } == true
        }
    }

    fun trust(
        records: Set<String>,
        packageName: String,
        signerDigests: Set<String>,
    ): Set<String> {
        require(packageName.matches(packageNamePattern)) { "Invalid plugin package name" }
        val normalizedDigests = requireNotNull(normalizeDigests(signerDigests)) {
            "Invalid plugin signer digest"
        }
        return records.filterNot { it.substringBefore('=') == packageName }.toSet() +
                encode(packageName, normalizedDigests)
    }

    fun trustLevel(
        hostSignatureMatches: Boolean,
        staticSignerMatches: Boolean,
        userConfirmationMatches: Boolean,
    ): TrustLevel = when {
        hostSignatureMatches || staticSignerMatches -> TrustLevel.ROOT
        userConfirmationMatches -> TrustLevel.USER
        else -> TrustLevel.NONE
    }

    fun mayUseExternalPlugin(requireRoot: Boolean, trustedForRoot: Boolean): Boolean {
        return !requireRoot || trustedForRoot
    }

    private fun encode(packageName: String, signerDigests: Set<String>): String {
        val digests = signerDigests.sorted().joinToString(",")
        return "$packageName=$digests"
    }

    private fun parse(record: String): Pair<String, Set<String>>? {
        val packageName = record.substringBefore('=')
        if (!packageName.matches(packageNamePattern)) return null
        val digests = record.substringAfter('=', "")
            .split(',')
            .filter(String::isNotBlank)
            .toSet()
        val normalizedDigests = normalizeDigests(digests) ?: return null
        return packageName to normalizedDigests
    }

    private fun normalizeDigests(signerDigests: Set<String>): Set<String>? {
        if (signerDigests.isEmpty()) return null
        return signerDigests.mapTo(linkedSetOf()) { digest ->
            digest.lowercase(Locale.ROOT).takeIf(digestPattern::matches) ?: return null
        }
    }
}
