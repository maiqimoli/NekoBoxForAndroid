package io.nekohasekai.sagernet.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSecurityTest {

    private val signerA = "a".repeat(64)
    private val signerB = "b".repeat(64)

    @Test
    fun trustIsBoundToPackageAndExactSignerSet() {
        val records = PluginSecurity.trust(emptySet(), "com.example.plugin", setOf(signerA))

        assertTrue(PluginSecurity.isTrusted(records, "com.example.plugin", setOf(signerA)))
        assertFalse(PluginSecurity.isTrusted(records, "com.example.other", setOf(signerA)))
        assertFalse(PluginSecurity.isTrusted(records, "com.example.plugin", setOf(signerB)))
        assertFalse(
            PluginSecurity.isTrusted(records, "com.example.plugin", setOf(signerA, signerB))
        )
    }

    @Test
    fun signerRemovalAlsoRequiresConfirmation() {
        val records = PluginSecurity.trust(
            emptySet(),
            "com.example.plugin",
            setOf(signerA, signerB),
        )

        assertFalse(PluginSecurity.isTrusted(records, "com.example.plugin", setOf(signerA)))
    }

    @Test
    fun newConfirmationReplacesPreviousSignerSet() {
        val original = PluginSecurity.trust(
            setOf("com.example.other=$signerA"),
            "com.example.plugin",
            setOf(signerA),
        )
        val updated = PluginSecurity.trust(original, "com.example.plugin", setOf(signerB))

        assertFalse(PluginSecurity.isTrusted(updated, "com.example.plugin", setOf(signerA)))
        assertTrue(PluginSecurity.isTrusted(updated, "com.example.plugin", setOf(signerB)))
        assertTrue(PluginSecurity.isTrusted(updated, "com.example.other", setOf(signerA)))
    }

    @Test
    fun userConfirmationNeverGrantsRootExecution() {
        val trust = PluginSecurity.trustLevel(
            hostSignatureMatches = false,
            staticSignerMatches = false,
            userConfirmationMatches = true,
        )

        assertEquals(PluginSecurity.TrustLevel.USER, trust)
        assertTrue(trust.canExecute)
        assertFalse(trust.canExecuteAsRoot)
    }

    @Test
    fun hostOrStaticSignerMayGrantRootExecution() {
        val hostTrust = PluginSecurity.trustLevel(true, false, false)
        val staticTrust = PluginSecurity.trustLevel(false, true, false)

        assertEquals(PluginSecurity.TrustLevel.ROOT, hostTrust)
        assertEquals(PluginSecurity.TrustLevel.ROOT, staticTrust)
        assertTrue(hostTrust.canExecuteAsRoot)
        assertTrue(staticTrust.canExecuteAsRoot)
    }

    @Test
    fun rootRequiredPluginSkipsUserTrustedExternalCandidate() {
        assertFalse(
            PluginSecurity.mayUseExternalPlugin(
                requireRoot = true,
                trustedForRoot = false,
            )
        )
        assertTrue(
            PluginSecurity.mayUseExternalPlugin(
                requireRoot = true,
                trustedForRoot = true,
            )
        )
        assertTrue(
            PluginSecurity.mayUseExternalPlugin(
                requireRoot = false,
                trustedForRoot = false,
            )
        )
    }

    @Test
    fun malformedTrustRecordsAreIgnored() {
        val malformedRecords = setOf(
            "com.example.plugin=not-a-digest",
            "invalid package=$signerA",
            "com.example.plugin=",
        )

        assertFalse(
            PluginSecurity.isTrusted(malformedRecords, "com.example.plugin", setOf(signerA))
        )
    }
}
