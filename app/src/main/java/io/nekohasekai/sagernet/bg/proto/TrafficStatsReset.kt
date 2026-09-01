package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.TrafficData

internal fun normalizeTrafficProfileIds(profileIds: LongArray): LongArray {
    val normalized = LinkedHashSet<Long>(profileIds.size)
    profileIds.forEach { id ->
        if (id > 0L) normalized.add(id)
    }
    return normalized.toLongArray()
}

internal fun resetTrafficAccumulator(
    item: TrafficUpdater.TrafficLooperData,
    nowMillis: Long = System.currentTimeMillis(),
) {
    item.rx = 0L
    item.tx = 0L
    item.rxBase = 0L
    item.txBase = 0L
    item.rxRate = 0L
    item.txRate = 0L
    item.lastUpdate = nowMillis
}

/**
 * Tracks the absolute traffic totals that were last committed to Room.
 *
 * [persistDirty] builds a dirty snapshot while the caller holds the traffic lock, passes that
 * snapshot to the transactional writer, and advances the baseline only after the writer returns.
 * A failed transaction therefore remains dirty and is retried by the next checkpoint or flush.
 */
internal class TrafficPersistenceState {
    private val persistedTotals = mutableMapOf<Long, Pair<Long, Long>>()

    fun reset(traffic: Collection<TrafficData>) {
        persistedTotals.clear()
        markPersisted(traffic)
    }

    fun dirtyTraffic(traffic: Collection<TrafficData>): List<TrafficData> =
        traffic.filter { item ->
            item.id > 0L && persistedTotals[item.id] != (item.rx to item.tx)
        }

    suspend fun persistDirty(
        traffic: Collection<TrafficData>,
        writer: suspend (List<TrafficData>) -> Unit,
    ) {
        val dirtyTraffic = dirtyTraffic(traffic)
        if (dirtyTraffic.isEmpty()) return
        writer(dirtyTraffic)
        markPersisted(dirtyTraffic)
    }

    private fun markPersisted(traffic: Collection<TrafficData>) {
        traffic.forEach { item ->
            if (item.id > 0L) {
                persistedTotals[item.id] = item.rx to item.tx
            }
        }
    }

    fun markCleared(profileIds: LongArray) {
        profileIds.forEach { id ->
            if (id > 0L) {
                persistedTotals[id] = 0L to 0L
            }
        }
    }
}
