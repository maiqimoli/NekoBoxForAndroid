package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionUpdaterTest {

    @Test
    fun `period is clamped to WorkManager minimum`() {
        assertEquals(15L, SubscriptionUpdater.normalizedPeriodMinutes(0))
        assertEquals(15L, SubscriptionUpdater.normalizedPeriodMinutes(5))
        assertEquals(60L, SubscriptionUpdater.normalizedPeriodMinutes(60))
    }

    @Test
    fun `not yet due subscription waits until its own deadline`() {
        assertEquals(
            1_800L,
            SubscriptionUpdater.secondsUntilDue(
                nowSeconds = 10_000L,
                lastUpdatedSeconds = 8_200,
                configuredMinutes = 60
            )
        )
    }

    @Test
    fun `overdue subscription runs immediately`() {
        assertEquals(
            0L,
            SubscriptionUpdater.secondsUntilDue(
                nowSeconds = 10_000L,
                lastUpdatedSeconds = 1_000,
                configuredMinutes = 60
            )
        )
    }

    @Test
    fun `minimum period is also used for initial delay`() {
        assertEquals(
            600L,
            SubscriptionUpdater.secondsUntilDue(
                nowSeconds = 10_000L,
                lastUpdatedSeconds = 9_700,
                configuredMinutes = 5
            )
        )
    }
}
