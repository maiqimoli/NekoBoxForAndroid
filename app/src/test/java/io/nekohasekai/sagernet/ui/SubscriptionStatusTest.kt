package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.SubscriptionBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionStatusTest {

    @Test
    fun parsesTrafficExpiryAndLastUpdatedFromUserInfo() {
        val subscription = subscriptionBean().apply {
            lastUpdated = 1_700_000_000
            subscriptionUserinfo =
                "upload=100; download=300; total=1000; expire=1800000000"
        }

        val status = SubscriptionStatus.from(subscription, nowEpochSeconds = 1_750_000_000L)

        assertEquals(400L, status.traffic?.usedBytes)
        assertEquals(600L, status.traffic?.remainingBytes)
        assertEquals(1_000L, status.traffic?.totalBytes)
        assertEquals(SubscriptionTrafficSource.USER_INFO, status.traffic?.source)
        assertEquals(1_700_000_000L, status.lastUpdatedEpochSeconds)
        assertEquals(1_800_000_000L, status.expiresAtEpochSeconds)
        assertEquals(SubscriptionExpiryState.ACTIVE, status.expiryState)
        assertFalse(status.isExpired)
    }

    @Test
    fun structuredFieldsTakePriorityOverUserInfo() {
        val subscription = subscriptionBean().apply {
            bytesUsed = 600L
            bytesRemaining = 400L
            expiryDate = 2_000
            subscriptionUserinfo =
                "upload=1; download=2; total=10; expire=3000"
        }

        val status = SubscriptionStatus.from(subscription, nowEpochSeconds = 1_000L)

        assertEquals(600L, status.traffic?.usedBytes)
        assertEquals(400L, status.traffic?.remainingBytes)
        assertEquals(1_000L, status.traffic?.totalBytes)
        assertEquals(SubscriptionTrafficSource.STRUCTURED, status.traffic?.source)
        assertEquals(2_000L, status.expiresAtEpochSeconds)
    }

    @Test
    fun exhaustedTrafficIsClampedAndExpiryAtNowIsExpired() {
        val subscription = subscriptionBean().apply {
            subscriptionUserinfo =
                "upload=700; download=500; total=1000; expire=2000"
        }

        val status = SubscriptionStatus.from(subscription, nowEpochSeconds = 2_000L)

        assertEquals(1_200L, status.traffic?.usedBytes)
        assertEquals(0L, status.traffic?.remainingBytes)
        assertEquals(SubscriptionExpiryState.EXPIRED, status.expiryState)
        assertTrue(status.isExpired)
    }

    @Test
    fun usageWithoutTotalKeepsRemainingUnknown() {
        val subscription = subscriptionBean().apply {
            subscriptionUserinfo = "upload=100; download=50"
        }

        val status = SubscriptionStatus.from(subscription, nowEpochSeconds = 1_000L)

        assertEquals(150L, status.traffic?.usedBytes)
        assertNull(status.traffic?.remainingBytes)
        assertNull(status.traffic?.totalBytes)
    }

    @Test
    fun missingMetadataProducesUnknownEmptyStatus() {
        val status = SubscriptionStatus.from(subscriptionBean(), nowEpochSeconds = 1_000L)

        assertNull(status.traffic)
        assertNull(status.lastUpdatedEpochSeconds)
        assertNull(status.expiresAtEpochSeconds)
        assertEquals(SubscriptionExpiryState.UNKNOWN, status.expiryState)
    }

    private fun subscriptionBean() = SubscriptionBean().apply {
        initializeDefaultValues()
    }
}
