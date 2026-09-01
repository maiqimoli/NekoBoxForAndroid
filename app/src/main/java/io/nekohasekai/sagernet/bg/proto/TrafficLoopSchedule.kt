package io.nekohasekai.sagernet.bg.proto

internal class TrafficLoopSchedule(
    val displayIntervalMs: Long,
    private val backgroundPollFloorMs: Long = 5_000L,
    val checkpointIntervalMs: Long = 60_000L,
) {
    init {
        require(displayIntervalMs >= 0L)
        require(backgroundPollFloorMs > 0L)
        require(checkpointIntervalMs > 0L)
    }

    val shouldDisplaySpeed: Boolean = displayIntervalMs > 0L

    fun nextDelayMs(hasDisplayConsumer: Boolean): Long {
        if (shouldDisplaySpeed && hasDisplayConsumer) return displayIntervalMs
        val collectionInterval = if (shouldDisplaySpeed) displayIntervalMs else backgroundPollFloorMs
        return maxOf(collectionInterval, backgroundPollFloorMs)
    }

    fun shouldCheckpoint(nowMs: Long, lastCheckpointMs: Long): Boolean {
        if (nowMs < lastCheckpointMs) return false
        return nowMs - lastCheckpointMs >= checkpointIntervalMs
    }
}
