package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import androidx.room.withTransaction
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.TAG_BYPASS
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val INITIALIZATION_POLL_INTERVAL_MS = 100L

private data class TrafficLoopResult(
    val speed: SpeedDisplayData?,
    val trafficUpdates: List<TrafficData>,
    val checkpointPersisted: Boolean,
)

class TrafficLooper
    (
    val data: BaseService.Data, private val sc: CoroutineScope
) {

    private var job: Job? = null
    private var trafficUpdater: TrafficUpdater? = null
    private val initializationMutex = Mutex()
    private val trafficMutex = Mutex()
    private val idMap = mutableMapOf<Long, TrafficUpdater.TrafficLooperData>() // id to 1 data
    private val tagMap = mutableMapOf<String, TrafficUpdater.TrafficLooperData>() // tag to 1 data
    private val lastBroadcastTraffic = mutableMapOf<Long, Pair<Long, Long>>()
    private val persistenceState = TrafficPersistenceState()
    // Protected by trafficMutex. Once stop() seals selector updates, a delayed native callback
    // cannot recreate mappings or persist traffic after the final shutdown flush.
    private var acceptingSelectorUpdates = true

    suspend fun <T> withInitializationLock(block: suspend () -> T): T =
        initializationMutex.withLock { block() }

    suspend fun stop() {
        val runningJob = job
        job = null
        runningJob?.cancelAndJoin()
        val traffic = trafficMutex.withLock {
            acceptingSelectorUpdates = false
            if (!DataStore.profileTrafficStatistics) {
                emptyList()
            } else {
                trafficUpdater?.updateAll()
                snapshotTrafficLocked().also { persistDirtyTrafficLocked(it) }
            }
        }
        data.binder.broadcast { b ->
            for (item in traffic) {
                b.cbTrafficUpdate(item)
            }
        }
        Logs.d("finally traffic post done")
    }

    suspend fun flush(): List<TrafficData> {
        if (!DataStore.profileTrafficStatistics) return emptyList()
        return trafficMutex.withLock {
            trafficUpdater?.updateAll()
            snapshotTrafficLocked().also { persistDirtyTrafficLocked(it) }
        }
    }

    suspend fun clearTrafficStats(profileIds: LongArray): List<TrafficData> {
        val normalizedIds = normalizeTrafficProfileIds(profileIds)
        if (normalizedIds.isEmpty()) return emptyList()
        val idSet = normalizedIds.toHashSet()

        return withInitializationLock {
            trafficMutex.withLock {
                // QueryStats consumes core counters. Consume everything accumulated before the
                // reset so the next query starts from a clean boundary.
                trafficUpdater?.updateAll()
                SagerDatabase.proxyDao.clearTraffic(normalizedIds)

                data.proxy?.let { proxy ->
                    if (proxy.profile.id in idSet) {
                        proxy.profile.rx = 0L
                        proxy.profile.tx = 0L
                    }
                    if (proxy.isConfigInitialized()) {
                        proxy.config.trafficMap.values.forEach { entities ->
                            entities.forEach { entity ->
                                if (entity.id in idSet) {
                                    entity.rx = 0L
                                    entity.tx = 0L
                                }
                            }
                        }
                    }
                }

                val now = System.currentTimeMillis()
                val updates = normalizedIds.map { id ->
                    idMap[id]?.let { resetTrafficAccumulator(it, now) }
                    lastBroadcastTraffic[id] = 0L to 0L
                    TrafficData(id = id, rx = 0L, tx = 0L)
                }
                // clearTraffic completed before the runtime baseline is advanced. If Room throws,
                // neither the accumulator reset nor the baseline reset is applied.
                persistenceState.markCleared(normalizedIds)
                updates
            }
        }
    }

    fun start() {
        check(job == null) { "Traffic looper is already running" }
        job = sc.launch(Dispatchers.Default) { loop() }
    }

    var selectorNowId = -114514L
    var selectorNowFakeTag = ""

    suspend fun selectMain(id: Long, isCurrent: () -> Boolean = { true }): Boolean {
        Logs.d("select traffic count $TAG_PROXY to $id, old id is $selectorNowId")
        return trafficMutex.withLock {
            if (!acceptingSelectorUpdates || !isCurrent()) return@withLock false
            // Consume pending deltas while TAG_PROXY still belongs to the old selector.
            trafficUpdater?.updateAll()
            selectMainLocked(id)?.let { persistDirtyTrafficLocked(listOf(it)) }
            true
        }
    }

    private fun selectMainLocked(id: Long): TrafficData? {
        val oldData = idMap[selectorNowId]
        val newData = idMap[id] ?: return null
        var completedTraffic: TrafficData? = null
        oldData?.apply {
            tag = selectorNowFakeTag
            ignore = true
            // post traffic when switch
            if (DataStore.profileTrafficStatistics && selectorNowId > 0L) {
                completedTraffic = TrafficData(id = selectorNowId, rx = rx, tx = tx)
            }
        }
        selectorNowFakeTag = newData.tag
        selectorNowId = id
        newData.apply {
            tag = TAG_PROXY
            ignore = false
        }
        return completedTraffic
    }

    private fun snapshotTrafficLocked(): List<TrafficData> = idMap.mapNotNull { (id, item) ->
        if (id <= 0L) null else TrafficData(id = id, rx = item.rx, tx = item.tx)
    }

    private suspend fun persistTraffic(traffic: Collection<TrafficData>) {
        if (traffic.isEmpty()) return
        SagerDatabase.instance.withTransaction {
            traffic.forEach {
                SagerDatabase.proxyDao.updateTraffic(
                    proxyId = it.id,
                    rx = it.rx,
                    tx = it.tx,
                )
            }
        }
    }

    /** Must be called with [trafficMutex] held. */
    private suspend fun persistDirtyTrafficLocked(traffic: Collection<TrafficData>) {
        persistenceState.persistDirty(traffic, ::persistTraffic)
    }

    private suspend fun loop() {
        val schedule = TrafficLoopSchedule(DataStore.speedInterval.toLong())
        val showDirectSpeed = DataStore.showDirectSpeed
        val profileTrafficStatistics = DataStore.profileTrafficStatistics
        if (!schedule.shouldDisplaySpeed && !profileTrafficStatistics) return

        // for display
        val itemBypass = TrafficUpdater.TrafficLooperData(tag = TAG_BYPASS)
        var hadForegroundCallback = false
        var lastCheckpointAt = SystemClock.elapsedRealtime()

        while (currentCoroutineContext().isActive) {
            val proxy = data.proxy
            if (proxy == null) {
                delay(schedule.nextDelayMs(hasDisplayConsumer = false))
                continue
            }

            if (trafficUpdater == null) {
                var initialized = false
                withInitializationLock {
                    if (data.proxy === proxy && proxy.isInitialized()) {
                        trafficMutex.withLock {
                            if (trafficUpdater == null) {
                                idMap.clear()
                                tagMap.clear()
                                lastBroadcastTraffic.clear()
                                idMap[-1] = itemBypass
                                val tags = hashSetOf(TAG_PROXY, TAG_BYPASS)
                                proxy.config.trafficMap.forEach { (tag, ents) ->
                                    tags.add(tag)
                                    for (ent in ents) {
                                        val item = TrafficUpdater.TrafficLooperData(
                                            tag = tag,
                                            rx = ent.rx,
                                            tx = ent.tx,
                                            rxBase = ent.rx,
                                            txBase = ent.tx,
                                            ignore = proxy.config.selectorGroupId >= 0L,
                                        )
                                        idMap[ent.id] = item
                                        tagMap[tag] = item
                                        Logs.d("traffic count $tag to ${ent.id}")
                                    }
                                }
                                if (proxy.config.selectorGroupId >= 0L) {
                                    selectMainLocked(proxy.config.mainEntId)
                                }
                                persistenceState.reset(snapshotTrafficLocked())
                                proxy.box.setV2rayStats(tags.joinToString("\n"))
                                trafficUpdater = TrafficUpdater(
                                    box = proxy.box,
                                    items = idMap.values.toList(),
                                )
                            }
                        }
                        initialized = trafficUpdater != null
                    }
                }
                if (!initialized) {
                    delay(INITIALIZATION_POLL_INTERVAL_MS)
                    continue
                }
            }

            val hasForegroundCallback = data.state == BaseService.State.Connected &&
                    data.binder.callbackIdMap.containsValue(
                        SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND
                    )
            val checkpointNow = SystemClock.elapsedRealtime()
            val shouldCheckpoint = profileTrafficStatistics &&
                    schedule.shouldCheckpoint(checkpointNow, lastCheckpointAt)
            val result = trafficMutex.withLock {
                trafficUpdater?.updateAll()

                val speed = if (schedule.shouldDisplaySpeed) {
                    var mainTxRate = 0L
                    var mainRxRate = 0L
                    var mainTx = 0L
                    var mainRx = 0L
                    tagMap.forEach { (_, item) ->
                        if (!item.ignore) {
                            mainTxRate += item.txRate
                            mainRxRate += item.rxRate
                        }
                        mainTx += item.tx - item.txBase
                        mainRx += item.rx - item.rxBase
                    }
                    SpeedDisplayData(
                        mainTxRate,
                        mainRxRate,
                        if (showDirectSpeed) itemBypass.txRate else 0L,
                        if (showDirectSpeed) itemBypass.rxRate else 0L,
                        mainTx,
                        mainRx,
                    )
                } else {
                    null
                }

                val forceTrafficSnapshot = hasForegroundCallback && !hadForegroundCallback
                val trafficUpdates = if (hasForegroundCallback && profileTrafficStatistics) {
                    buildList {
                        idMap.forEach { (id, item) ->
                            if (id > 0L) {
                                val current = item.rx to item.tx
                                if (forceTrafficSnapshot || lastBroadcastTraffic[id] != current) {
                                    add(TrafficData(id = id, rx = item.rx, tx = item.tx))
                                    lastBroadcastTraffic[id] = current
                                }
                            }
                        }
                    }
                } else {
                    emptyList()
                }
                var checkpointPersisted = false
                if (shouldCheckpoint) {
                    try {
                        // Keep snapshot creation and its absolute UPDATE in the same critical
                        // section so an older selector/flush snapshot can never land later.
                        persistDirtyTrafficLocked(snapshotTrafficLocked())
                        checkpointPersisted = true
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Logs.w("Failed to persist traffic checkpoint", error)
                    }
                }
                TrafficLoopResult(
                    speed = speed,
                    trafficUpdates = trafficUpdates,
                    checkpointPersisted = checkpointPersisted,
                )
            }
            if (!currentCoroutineContext().isActive) return

            if (result.checkpointPersisted) {
                lastCheckpointAt = checkpointNow
            }

            if (hasForegroundCallback &&
                (result.speed != null || result.trafficUpdates.isNotEmpty())
            ) {
                data.binder.broadcast { b ->
                    if (data.binder.callbackIdMap[b] == SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND) {
                        result.speed?.let(b::cbSpeedUpdate)
                        result.trafficUpdates.forEach(b::cbTrafficUpdate)
                    }
                }
            }
            hadForegroundCallback = hasForegroundCallback

            // ServiceNotification
            result.speed?.let { speed ->
                data.notification?.apply {
                    if (!hasForegroundCallback && listenPostSpeed) {
                        postNotificationSpeedUpdate(speed)
                    }
                }
            }

            val notificationActive = schedule.shouldDisplaySpeed &&
                    data.notification?.listenPostSpeed == true
            delay(schedule.nextDelayMs(hasForegroundCallback || notificationActive))
        }
    }
}
