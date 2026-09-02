package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionImportHelperTest {

    @Test
    fun shouldRecognizeSupportedSubscriptionSchemes() {
        assertTrue(SubscriptionImportHelper.shouldImportSubscription("sn", "subscription"))
        assertTrue(SubscriptionImportHelper.shouldImportSubscription("SN", "SUBSCRIPTION"))
        assertTrue(SubscriptionImportHelper.shouldImportSubscription("clash", "install-config"))
        assertTrue(SubscriptionImportHelper.shouldImportSubscription("clash", null))
        assertFalse(SubscriptionImportHelper.shouldImportSubscription("sn", "profile"))
        assertFalse(SubscriptionImportHelper.shouldImportSubscription("https", "subscription"))
    }

    @Test
    fun createDirectSubscriptionGroupUsesExplicitName() {
        val group = SubscriptionImportHelper.createDirectSubscriptionGroup(
            "sn",
            "subscription",
            null,
            null,
            null,
        ) { key ->
            when (key) {
                "url" -> " https://wj-kc.com/api/subscript/demo "
                "name" -> "My Feed"
                else -> null
            }
        }

        assertNotNull(group)
        assertEquals("My Feed", group?.name)
        assertEquals("https://wj-kc.com/api/subscript/demo", group?.subscription?.link)
    }

    @Test
    fun createDirectSubscriptionGroupSupportsAlternativeKeys() {
        val group = SubscriptionImportHelper.createDirectSubscriptionGroup(
            "clash",
            "install-config",
            null,
            null,
            null,
        ) { key ->
            when (key) {
                "subscription" -> "https://example.com/sub"
                "title" -> "Imported Clash Feed"
                else -> null
            }
        }

        assertNotNull(group)
        assertEquals("Imported Clash Feed", group?.name)
        assertEquals("https://example.com/sub", group?.subscription?.link)
    }

    @Test
    fun createDirectSubscriptionGroupSupportsEncodedPath() {
        val group = SubscriptionImportHelper.createDirectSubscriptionGroup(
            "clash",
            "install-config",
            "/https%3A%2F%2Fexample.com%2Fpath%2Fsub",
            null,
            null,
        ) { null }

        assertNotNull(group)
        assertEquals("https://example.com/path/sub", group?.subscription?.link)
        assertEquals("example.com", group?.name)
    }

    @Test
    fun createDirectSubscriptionGroupRejectsMalformedEncodedPath() {
        assertThrows(MalformedSubscriptionLinkException::class.java) {
            SubscriptionImportHelper.createDirectSubscriptionGroup(
                "clash",
                "install-config",
                "/https%3A%2F%",
                null,
                null,
            ) { null }
        }
    }

    @Test
    fun createDirectSubscriptionGroupSupportsRawEncodedQueryUrl() {
        val group = SubscriptionImportHelper.createDirectSubscriptionGroup(
            "sn",
            "subscription",
            null,
            "https%3A%2F%2Fexample.com%2Fsub%3Ffoo%3D1%26bar%3D2",
            null,
        ) { null }

        assertNotNull(group)
        assertEquals("https://example.com/sub?foo=1&bar=2", group?.subscription?.link)
    }

    @Test
    fun createDirectSubscriptionGroupSupportsFragmentParameters() {
        val group = SubscriptionImportHelper.createDirectSubscriptionGroup(
            "sn",
            "subscription",
            null,
            null,
            "url=https%3A%2F%2Fexample.com%2Fsub&name=FragmentFeed",
        ) { null }

        assertNotNull(group)
        assertEquals("FragmentFeed", group?.name)
        assertEquals("https://example.com/sub", group?.subscription?.link)
    }

    @Test
    fun createDirectSubscriptionGroupRejectsMalformedLooseParameters() {
        assertThrows(MalformedSubscriptionLinkException::class.java) {
            SubscriptionImportHelper.createDirectSubscriptionGroup(
                "sn",
                "subscription",
                null,
                "url=https%3A%2F%2Fexample.com&broken=%ZZ",
                null,
            ) { null }
        }
    }

    @Test
    fun createDirectSubscriptionGroupNormalizesQueryParserFailures() {
        assertThrows(MalformedSubscriptionLinkException::class.java) {
            SubscriptionImportHelper.createDirectSubscriptionGroup(
                "sn",
                "subscription",
                null,
                null,
                null,
            ) {
                throw IllegalArgumentException("bad escape")
            }
        }
    }

    @Test
    fun createDirectSubscriptionGroupFallsBackToHostName() {
        val group = SubscriptionImportHelper.createDirectSubscriptionGroup(
            "sn",
            "subscription",
            null,
            null,
            null,
        ) { key ->
            when (key) {
                "url" -> "https://www.example.com/path/subscription?id=1"
                else -> null
            }
        }

        assertNotNull(group)
        assertEquals("example.com", group?.name)
    }

    @Test
    fun createDirectSubscriptionGroupRejectsInvalidUrls() {
        val invalid = SubscriptionImportHelper.createDirectSubscriptionGroup(
            "sn",
            "subscription",
            null,
            null,
            null,
        ) { key ->
            when (key) {
                "url" -> "not a url"
                else -> null
            }
        }

        assertNull(invalid)
    }

    @Test
    fun fallbackGroupNameUsesTokenThenTimestamp() {
        val tokenGroup = SubscriptionImportHelper.createDirectSubscriptionGroup(
            "sn",
            "subscription",
            null,
            null,
            null,
        ) { key ->
            when (key) {
                "url" -> "https://example.com/sub"
                else -> null
            }
        }!!.apply {
            name = null
            subscription!!.token = "abc-token"
            subscription!!.link = ""
        }

        assertEquals("abc-token", SubscriptionImportHelper.fallbackGroupName(tokenGroup, 42L))

        tokenGroup.subscription!!.token = ""
        assertEquals("Subscription #42", SubscriptionImportHelper.fallbackGroupName(tokenGroup, 42L))
    }
}
