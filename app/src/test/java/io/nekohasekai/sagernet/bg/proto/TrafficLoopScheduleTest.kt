package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficLoopScheduleTest {

    @Test
    fun disabledDisplayUsesStatisticsPollInterval() {
        val schedule = TrafficLoopSchedule(displayIntervalMs = 0L)

        assertFalse(schedule.shouldDisplaySpeed)
        assertEquals(5_000L, schedule.nextDelayMs(hasDisplayConsumer = true))
        assertEquals(5_000L, schedule.nextDelayMs(hasDisplayConsumer = false))
    }

    @Test
    fun enabledDisplayKeepsForegroundAndBackgroundIntervals() {
        val schedule = TrafficLoopSchedule(displayIntervalMs = 1_000L)

        assertTrue(schedule.shouldDisplaySpeed)
        assertEquals(1_000L, schedule.nextDelayMs(hasDisplayConsumer = true))
        assertEquals(5_000L, schedule.nextDelayMs(hasDisplayConsumer = false))
    }

    @Test
    fun checkpointUsesMonotonicBoundary() {
        val schedule = TrafficLoopSchedule(
            displayIntervalMs = 1_000L,
            checkpointIntervalMs = 60_000L,
        )

        assertFalse(schedule.shouldCheckpoint(59_999L, 0L))
        assertTrue(schedule.shouldCheckpoint(60_000L, 0L))
        assertFalse(schedule.shouldCheckpoint(99L, 100L))
    }
}
