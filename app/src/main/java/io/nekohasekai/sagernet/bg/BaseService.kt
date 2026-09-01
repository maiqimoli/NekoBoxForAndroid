package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Network
import android.os.*
import android.widget.Toast
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.bg.proto.normalizeTrafficProfileIds
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.AutoRegionManager
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.utils.Util
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

private const val EXTRA_INTERNAL_RESTART_GENERATION =
    "io.nekohasekai.sagernet.extra.INTERNAL_RESTART_GENERATION"
private const val EXTRA_INTERNAL_RESTART_STOP_GENERATION =
    "io.nekohasekai.sagernet.extra.INTERNAL_RESTART_STOP_GENERATION"
private const val SERVICE_STOP_TIMEOUT_MS = 30_000L

private fun Throwable?.mergeShutdownFailure(next: Throwable): Throwable =
    this?.also { current ->
        if (current !== next) current.addSuppressed(next)
    } ?: next

class BaseService {

    enum class State(
        val canStop: Boolean = false,
        val started: Boolean = false,
        val connected: Boolean = false,
    ) {
        /**
         * Idle state is only used by UI and will never be returned by BaseService.
         */
        Idle, Connecting(true, true, false), Connected(true, true, true), Stopping, Stopped,
    }

    interface ExpectedException

    data class StopResult(
        val success: Boolean,
        val restarted: Boolean,
    )

    class Data internal constructor(private val service: Interface) {
        @Volatile
        var state = State.Stopped
        var proxy: ProxyInstance? = null
        var notification: ServiceNotification? = null

        val receiver = broadcastReceiver { ctx, intent ->
            when (intent.action) {
                Intent.ACTION_SHUTDOWN -> service.persistStats()
                Action.RELOAD -> service.reload()
                // Action.SWITCH_WAKE_LOCK -> runOnDefaultDispatcher { service.switchWakeLock() }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    if (SagerNet.power.isDeviceIdleMode) {
                        proxy?.box?.sleep()
                    } else {
                        proxy?.box?.wake()
                        if (DataStore.wakeResetConnections) {
                            Libcore.resetAllConnections(true)
                        }
                    }
                }

                Action.RESET_UPSTREAM_CONNECTIONS -> runOnDefaultDispatcher {
                    Libcore.resetAllConnections(true)
                    runOnMainDispatcher {
                        Util.collapseStatusBar(ctx)
                        Toast.makeText(ctx, "Reset upstream connections done", Toast.LENGTH_SHORT)
                            .show()
                    }
                }

                else -> service.stopRunner()
            }
        }
        var closeReceiverRegistered = false

        val binder = Binder(this)
        var connectingJob: Job? = null
        var reloadJob: Job? = null
        val reloadLock = Any()
        var reloadGeneration = 0L
        val stoppingLock = Any()

        @Volatile
        var stoppingDeferred: Deferred<StopResult>? = null
        var generationCounter = 0L
        var startGeneration = 0L
        var stoppingGeneration = 0L
        var internalRestartGeneration = 0L
        var internalRestartStopGeneration = 0L
        var restartRequested = false
        var hardStopRequested = false
        var stoppingMessage: String? = null
        var selectorCallbackGeneration = 0L

        fun nextGenerationLocked(): Long {
            generationCounter += 1L
            return generationCounter
        }

        fun invalidateReload(): Job? = synchronized(reloadLock) {
            reloadGeneration += 1L
            reloadJob.also { reloadJob = null }
        }?.also(Job::cancel)

        fun requestStop(restart: Boolean = false, msg: String? = null): Deferred<StopResult> =
            service.stopRunner(restart, msg)

        fun changeState(s: State, msg: String? = null) {
            if (state == s && msg == null) return
            state = s
            DataStore.serviceState = s
            binder.stateChanged(s, msg)
        }
    }

    class Binder(private var data: Data? = null) : ISagerNetService.Stub(), CoroutineScope,
        AutoCloseable {
        private val callbacks = object : RemoteCallbackList<ISagerNetServiceCallback>() {
            override fun onCallbackDied(callback: ISagerNetServiceCallback?, cookie: Any?) {
                super.onCallbackDied(callback, cookie)
                callback?.let(callbackIdMap::remove)
            }
        }

        val callbackIdMap = ConcurrentHashMap<ISagerNetServiceCallback, Int>()

        override val coroutineContext = Dispatchers.Main.immediate + Job()

        override fun getState(): Int = (data?.state ?: State.Idle).ordinal
        override fun getProfileName(): String = data?.proxy?.displayProfileName ?: "Idle"

        override fun registerCallback(cb: ISagerNetServiceCallback, id: Int) {
            if (id == SagerConnection.CONNECTION_ID_RESTART_BG) {
                Runtime.getRuntime().exit(0)
                return
            }
            if (!callbackIdMap.contains(cb)) {
                callbacks.register(cb)
            }
            callbackIdMap[cb] = id
        }

        private val broadcastMutex = Mutex()

        suspend fun broadcast(work: (ISagerNetServiceCallback) -> Unit) {
            broadcastMutex.withLock {
                val count = callbacks.beginBroadcast()
                try {
                    repeat(count) {
                        try {
                            work(callbacks.getBroadcastItem(it))
                        } catch (e: RemoteException) {
                            Logs.w(e)
                        } catch (e: Exception) {
                            Logs.w(e)
                        }
                    }
                } finally {
                    callbacks.finishBroadcast()
                }
            }
        }

        override fun unregisterCallback(cb: ISagerNetServiceCallback) {
            callbackIdMap.remove(cb)
            callbacks.unregister(cb)
        }

        override fun clearTrafficStats(profileIds: LongArray) {
            val normalizedIds = normalizeTrafficProfileIds(profileIds)
            if (normalizedIds.isEmpty()) return
            val current = checkNotNull(data) { "Service binder is detached" }

            runBlocking(Dispatchers.IO) {
                val proxy = current.proxy
                val updates = when {
                    proxy?.looper != null -> proxy.looper.clearTrafficStats(normalizedIds)
                    current.state == State.Stopped && proxy == null -> {
                        SagerDatabase.proxyDao.clearTraffic(normalizedIds)
                        normalizedIds.map { id -> TrafficData(id, 0L, 0L) }
                    }

                    else -> error(
                        "Traffic controller is unavailable while service is ${current.state}"
                    )
                }
                broadcast { callback ->
                    updates.forEach(callback::cbTrafficUpdate)
                }
            }
        }

        override fun stopAndWait(): Boolean {
            val current = data ?: return true
            // A local Binder can be invoked directly from the UI thread. Scheduling the stop is
            // still useful there, but blocking would deadlock the Main-dispatched cleanup.
            if (Looper.myLooper() == Looper.getMainLooper()) {
                current.requestStop(restart = false)
                return current.state == State.Stopped && current.proxy == null
            }

            return runBlocking {
                withTimeoutOrNull(SERVICE_STOP_TIMEOUT_MS) {
                    var result = current.requestStop(restart = false).await()
                    // A reload request may have raced with this hard stop after cleanup selected
                    // the restart path. Make the hard-stop sticky and drain that generation too.
                    if (result.restarted || current.state != State.Stopped || current.proxy != null) {
                        result = current.requestStop(restart = false).await()
                    }
                    result.success && !result.restarted &&
                            current.state == State.Stopped && current.proxy == null
                } ?: false
            }
        }

        override fun urlTest(): Int {
            if (data?.proxy?.box == null) {
                error("core not started")
            }
            try {
                return Libcore.urlTest(
                    data!!.proxy!!.box, DataStore.connectionTestURL, 3000
                )
            } catch (e: Exception) {
                error(Protocols.genFriendlyMsg(e.readableMessage))
            }
        }

        fun stateChanged(s: State, msg: String?) = launch {
            val profileName = profileName
            broadcast { it.stateChanged(s.ordinal, profileName, msg) }
        }

        fun missingPlugin(pluginName: String) = launch {
            val profileName = profileName
            broadcast { it.missingPlugin(profileName, pluginName) }
        }

        override fun close() {
            callbacks.kill()
            cancel()
            data = null
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createNotification(profileName: String): ServiceNotification

        fun onBind(intent: Intent): IBinder? =
            if (intent.action == Action.SERVICE) data.binder else null

        fun reload() {
            this as Context
            val selectedProfileId = DataStore.selectedProxy
            if (selectedProfileId == 0L) {
                data.invalidateReload()
                stopRunner(false, getString(R.string.profile_empty))
                return
            }

            val s = data.state
            when {
                s == State.Stopped -> {
                    data.invalidateReload()
                    startRunner()
                    return
                }

                !s.canStop -> {
                    data.invalidateReload()
                    Logs.w("Illegal state $s when invoking use")
                    return
                }
            }

            val activeProxy = data.proxy
            if (activeProxy == null || !activeProxy.isInitialized() ||
                activeProxy.config.selectorGroupId < 0L
            ) {
                data.invalidateReload()
                stopRunner(true)
                return
            }

            lateinit var reloadJob: Job
            val previousReloadJob: Job?
            val reloadGeneration: Long
            synchronized(data.reloadLock) {
                data.reloadGeneration += 1L
                reloadGeneration = data.reloadGeneration
                previousReloadJob = data.reloadJob
                reloadJob = data.binder.launch(start = CoroutineStart.LAZY) {
                val currentJob = coroutineContext[Job]
                try {
                    val candidateSelectorGroupId = withContext(Dispatchers.IO) {
                        SagerDatabase.proxyDao.getById(selectedProfileId)?.let { profile ->
                            ProxyInstance(profile).run {
                                buildConfigTmp()
                                lastSelectorGroupId
                            }
                        }
                    }

                    synchronized(data.reloadLock) {
                        if (data.reloadGeneration != reloadGeneration ||
                            data.reloadJob !== currentJob
                        ) return@synchronized

                        val canApply = synchronized(data.stoppingLock) {
                            data.proxy === activeProxy &&
                                    data.state == State.Connected &&
                                    data.stoppingDeferred?.isCompleted != false &&
                                    DataStore.selectedProxy == selectedProfileId &&
                                    activeProxy.isInitialized()
                        }
                        if (!canApply) return@synchronized

                        val tag = activeProxy.config.profileTagMap[selectedProfileId].orEmpty()
                        if (candidateSelectorGroupId == activeProxy.lastSelectorGroupId &&
                            tag.isNotBlank()
                        ) {
                            // Keep validation and the selector mutation in one reload generation.
                            // A newer reload cannot replace this job between the check and call.
                            if (!activeProxy.box.selectOutbound(tag)) {
                                // The selector may have closed, or its outbound set may have changed
                                // since the temporary config was built. Rebuild instead of silently
                                // keeping the previous route active.
                                stopRunner(true)
                            }
                        } else {
                            // A deleted profile, changed selector graph, or missing tag needs a
                            // complete rebuild instead of silently leaving the old selection active.
                            stopRunner(true)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Logs.w("Failed to evaluate selector reload; rebuilding service", error)
                    synchronized(data.reloadLock) {
                        if (data.reloadGeneration != reloadGeneration ||
                            data.reloadJob !== currentJob
                        ) return@synchronized
                        val shouldRestart = synchronized(data.stoppingLock) {
                            data.proxy === activeProxy &&
                                    data.state.canStop &&
                                    data.stoppingDeferred?.isCompleted != false &&
                                    DataStore.selectedProxy == selectedProfileId
                        }
                        if (shouldRestart) stopRunner(true)
                    }
                } finally {
                    synchronized(data.reloadLock) {
                        if (data.reloadGeneration == reloadGeneration &&
                            data.reloadJob === currentJob
                        ) {
                            data.reloadJob = null
                        }
                    }
                }
            }
                data.reloadJob = reloadJob
            }
            previousReloadJob?.cancel()
            reloadJob.start()
        }

        suspend fun startProcesses() {
            data.proxy!!.launch()
        }

        fun startRunner(
            internalRestartGeneration: Long = 0L,
            stoppingGeneration: Long = 0L,
        ) {
            this as Context
            val intent = Intent(this, javaClass).apply {
                if (internalRestartGeneration != 0L || stoppingGeneration != 0L) {
                    putExtra(EXTRA_INTERNAL_RESTART_GENERATION, internalRestartGeneration)
                    putExtra(EXTRA_INTERNAL_RESTART_STOP_GENERATION, stoppingGeneration)
                }
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent)
            else startService(intent)
        }

        suspend fun killProcesses() {
            var failure: Throwable? = null
            try {
                data.proxy?.closeAndWait()
            } catch (error: Throwable) {
                failure = failure.mergeShutdownFailure(error)
            }
            try {
                wakeLock?.release()
            } catch (error: Throwable) {
                failure = failure.mergeShutdownFailure(error)
            } finally {
                wakeLock = null
            }
            try {
                DefaultNetworkListener.stop(this)
            } catch (error: Throwable) {
                failure = failure.mergeShutdownFailure(error)
            }
            failure?.let { throw it }
        }

        fun stopRunner(restart: Boolean = false, msg: String? = null): Deferred<StopResult> {
            this as Service
            // Keep the global lock order reloadLock -> stoppingLock.  In particular reload()
            // can request a restart while it still owns reloadLock.
            val invalidatedReloadJob = data.invalidateReload()
            val stopDeferred = synchronized(data.stoppingLock) {
                if (restart) {
                    if (!data.hardStopRequested) {
                        data.startGeneration = data.nextGenerationLocked()
                        data.restartRequested = true
                    }
                } else {
                    data.hardStopRequested = true
                    data.restartRequested = false
                    data.startGeneration = 0L
                    data.internalRestartGeneration = 0L
                    data.internalRestartStopGeneration = 0L
                }
                if (msg != null || data.stoppingMessage == null) data.stoppingMessage = msg

                val activeStop = data.stoppingDeferred?.takeUnless { it.isCompleted }
                if (activeStop != null) return@synchronized activeStop

                // A transient Binder may create the service without starting a proxy. Avoid
                // manufacturing a Stopping generation when there is no resource left to drain.
                if (!restart && msg == null && data.state == State.Stopped &&
                    data.proxy == null && data.connectingJob == null && data.reloadJob == null &&
                    data.notification == null && !data.closeReceiverRegistered &&
                    wakeLock == null && data.internalRestartGeneration == 0L
                ) {
                    data.stoppingMessage = null
                    return@synchronized CompletableDeferred(
                        StopResult(success = true, restarted = false)
                    )
                }

                val stopGeneration = data.nextGenerationLocked()
                data.stoppingGeneration = stopGeneration
                val connectingJobToJoin = data.connectingJob
                var stoppedCleanly = false
                lateinit var createdDeferred: Deferred<StopResult>
                createdDeferred = data.binder.async(
                    context = Dispatchers.Default,
                    start = CoroutineStart.LAZY,
                ) {
                    withContext(NonCancellable) {
                        var failure: Throwable? = null
                        withContext(Dispatchers.Main.immediate) {
                            try {
                                DataStore.baseService = null
                                DataStore.vpnService = null
                            } catch (error: Throwable) {
                                failure = failure.mergeShutdownFailure(error)
                            }
                            try {
                                data.changeState(State.Stopping)
                            } catch (error: Throwable) {
                                failure = failure.mergeShutdownFailure(error)
                            }
                        }

                        try {
                            connectingJobToJoin?.cancelAndJoin() // ensure stop connecting first
                        } catch (error: Throwable) {
                            failure = failure.mergeShutdownFailure(error)
                        } finally {
                            synchronized(data.stoppingLock) {
                                if (data.connectingJob === connectingJobToJoin) {
                                    data.connectingJob = null
                                }
                            }
                        }

                        val lateReloadJob = data.invalidateReload()
                        try {
                            invalidatedReloadJob?.cancelAndJoin()
                            if (lateReloadJob !== invalidatedReloadJob) {
                                lateReloadJob?.cancelAndJoin()
                            }
                        } catch (error: Throwable) {
                            failure = failure.mergeShutdownFailure(error)
                        }

                        try {
                            withContext(Dispatchers.IO) { killProcesses() }
                        } catch (error: Throwable) {
                            failure = failure.mergeShutdownFailure(error)
                        }

                        withContext(Dispatchers.Main.immediate) {
                            if (data.closeReceiverRegistered) {
                                try {
                                    unregisterReceiver(data.receiver)
                                } catch (error: Throwable) {
                                    failure = failure.mergeShutdownFailure(error)
                                } finally {
                                    data.closeReceiverRegistered = false
                                }
                            }
                            data.proxy = null
                            val decision = synchronized(data.stoppingLock) {
                                val latestStartGeneration = data.startGeneration
                                val ownsStop = data.stoppingDeferred === createdDeferred &&
                                        data.stoppingGeneration == stopGeneration
                                val shouldRestart = ownsStop && failure == null &&
                                        data.restartRequested && !data.hardStopRequested &&
                                        latestStartGeneration != 0L
                                val message = data.stoppingMessage
                                data.restartRequested = false
                                data.stoppingMessage = null
                                if (shouldRestart) {
                                    data.internalRestartGeneration = latestStartGeneration
                                    data.internalRestartStopGeneration = stopGeneration
                                } else if (ownsStop) {
                                    data.internalRestartGeneration = 0L
                                    data.internalRestartStopGeneration = 0L
                                }
                                Triple(shouldRestart, message, latestStartGeneration)
                            }
                            try {
                                data.changeState(State.Stopped, decision.second)
                            } catch (error: Throwable) {
                                failure = failure.mergeShutdownFailure(error)
                            }

                            val shouldRestart = decision.first && failure == null
                            var restartLaunched = false
                            var shouldStopService = !shouldRestart
                            if (shouldRestart) {
                                try {
                                    startRunner(decision.third, stopGeneration)
                                    restartLaunched = true
                                } catch (error: Throwable) {
                                    failure = failure.mergeShutdownFailure(error)
                                    shouldStopService = synchronized(data.stoppingLock) {
                                        val ownsRestartTicket =
                                            data.startGeneration == decision.third &&
                                                    data.internalRestartGeneration == decision.third &&
                                                    data.internalRestartStopGeneration == stopGeneration
                                        if (ownsRestartTicket) {
                                            data.startGeneration = 0L
                                            data.restartRequested = false
                                            data.internalRestartGeneration = 0L
                                            data.internalRestartStopGeneration = 0L
                                        }
                                        ownsRestartTicket
                                    }
                                }
                            }
                            if (shouldStopService) {
                                val notificationToDestroy = synchronized(data.stoppingLock) {
                                    data.notification.also { data.notification = null }
                                }
                                try {
                                    notificationToDestroy?.destroy()
                                } catch (error: Throwable) {
                                    failure = failure.mergeShutdownFailure(error)
                                }
                                stopSelf()
                            }
                            if (failure != null) {
                                Logs.w("Failed to stop service cleanly", failure!!)
                            }
                            stoppedCleanly = failure == null
                            StopResult(success = stoppedCleanly, restarted = restartLaunched)
                        }
                    }
                }
                data.stoppingDeferred = createdDeferred
                createdDeferred.invokeOnCompletion {
                    var lateRestart: Pair<Long, Long>? = null
                    var finishLateHardStop = false
                    synchronized(data.stoppingLock) {
                        if (data.stoppingDeferred === createdDeferred &&
                            data.stoppingGeneration == stopGeneration
                        ) {
                            data.stoppingDeferred = null
                            if (data.state == State.Stopped && data.hardStopRequested &&
                                data.proxy == null
                            ) {
                                finishLateHardStop = true
                            } else if (stoppedCleanly && data.state == State.Stopped &&
                                data.restartRequested && !data.hardStopRequested &&
                                data.startGeneration != 0L
                            ) {
                                data.restartRequested = false
                                data.internalRestartGeneration = data.startGeneration
                                data.internalRestartStopGeneration = stopGeneration
                                lateRestart = data.startGeneration to stopGeneration
                            }
                        }
                    }
                    if (finishLateHardStop) {
                        data.binder.launch(Dispatchers.Main.immediate) {
                            var shouldStopService = false
                            val notificationToDestroy = synchronized(data.stoppingLock) {
                                val stillStopped = data.state == State.Stopped &&
                                        data.hardStopRequested && data.proxy == null &&
                                        data.startGeneration == 0L &&
                                        data.internalRestartGeneration == 0L &&
                                        data.internalRestartStopGeneration == 0L &&
                                        data.stoppingDeferred?.isActive != true
                                shouldStopService = stillStopped
                                if (stillStopped) {
                                    data.notification.also { data.notification = null }
                                } else {
                                    null
                                }
                            }
                            if (shouldStopService) {
                                try {
                                    notificationToDestroy?.destroy()
                                } catch (error: Throwable) {
                                    Logs.w("Failed to finish a late hard stop", error)
                                }
                                stopSelf()
                            }
                        }
                    } else {
                        lateRestart?.let { (startTicket, stopTicket) ->
                            try {
                                startRunner(startTicket, stopTicket)
                            } catch (error: Throwable) {
                                Logs.w("Failed to launch a queued service restart", error)
                                data.binder.launch(Dispatchers.Main.immediate) {
                                    var ownsFailedRestart = false
                                    val notificationToDestroy = synchronized(data.stoppingLock) {
                                        ownsFailedRestart = data.state == State.Stopped &&
                                                data.proxy == null &&
                                                data.startGeneration == startTicket &&
                                                data.internalRestartGeneration == startTicket &&
                                                data.internalRestartStopGeneration == stopTicket
                                        if (ownsFailedRestart) {
                                            data.startGeneration = 0L
                                            data.restartRequested = false
                                            data.internalRestartGeneration = 0L
                                            data.internalRestartStopGeneration = 0L
                                            data.notification.also { data.notification = null }
                                        } else {
                                            null
                                        }
                                    }
                                    if (ownsFailedRestart) {
                                        try {
                                            notificationToDestroy?.destroy()
                                        } catch (destroyError: Throwable) {
                                            Logs.w(
                                                "Failed to release the foreground notification",
                                                destroyError,
                                            )
                                        }
                                        stopSelf()
                                    }
                                }
                            }
                        }
                    }
                }
                createdDeferred
            }
            stopDeferred.start()
            return stopDeferred
        }

        fun persistStats() {
            val trafficLooper = data.proxy?.looper ?: return
            data.binder.launch(Dispatchers.IO) {
                try {
                    trafficLooper.flush()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Logs.w("Failed to persist traffic snapshot", error)
                }
            }
        }

        // networks
        var upstreamInterfaceName: String?
        var upstreamNetwork: Network?

        suspend fun preInit() {
            DefaultNetworkListener.start(this) { network ->
                SagerNet.underlyingNetwork = network
                DataStore.vpnService?.updateUnderlyingNetwork(network = network)
                if (network == null) {
                    upstreamNetwork = null
                    upstreamInterfaceName = null
                    return@start
                }

                val link = SagerNet.connectivity.getLinkProperties(network) ?: return@start
                val oldNetwork = upstreamNetwork
                val oldName = upstreamInterfaceName
                upstreamNetwork = network
                upstreamInterfaceName = link.interfaceName
                if (oldNetwork != null && oldNetwork != network) {
                    Logs.d("Network changed: $oldName/$oldNetwork -> ${link.interfaceName}/$network")
                    if (DataStore.networkChangeResetConnections) {
                        Libcore.resetAllConnections(true)
                    }
                }
            }
        }

        var wakeLock: PowerManager.WakeLock?
        fun acquireWakeLock()

        fun lateInit(
            connectingJob: Job,
            startGeneration: Long,
            stoppingGeneration: Long,
            proxy: ProxyInstance,
            notification: ServiceNotification,
        ): Boolean = synchronized(data.stoppingLock) {
            if (data.connectingJob !== connectingJob ||
                data.startGeneration != startGeneration ||
                data.stoppingGeneration != stoppingGeneration ||
                data.state != State.Connecting ||
                data.proxy !== proxy ||
                data.notification !== notification ||
                data.stoppingDeferred?.isActive == true ||
                data.hardStopRequested
            ) return@synchronized false

            wakeLock?.release()
            wakeLock = null

            val acquireWakeLock = DataStore.acquireWakeLock
            if (acquireWakeLock) acquireWakeLock()
            check(notification.postNotificationWakeLockStatus(acquireWakeLock)) {
                "Foreground notification was destroyed during startup"
            }
            data.changeState(State.Connected)
            true
        }

        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            val data = data
            this as Context
            val requestedStartGeneration =
                intent?.getLongExtra(EXTRA_INTERNAL_RESTART_GENERATION, 0L) ?: 0L
            val requestedStopGeneration =
                intent?.getLongExtra(EXTRA_INTERNAL_RESTART_STOP_GENERATION, 0L) ?: 0L
            val internalRestart = requestedStartGeneration != 0L || requestedStopGeneration != 0L

            var acceptedStartGeneration = 0L
            var expectedStopGeneration = 0L
            var stopToAwait: Deferred<StopResult>? = null
            var queueForStoppingGeneration = false
            var rejectInternalStart = false
            var terminateRejectedStart = false
            var ignoreAlreadyRunning = false
            synchronized(data.stoppingLock) {
                if (internalRestart) {
                    val valid = requestedStartGeneration != 0L &&
                            requestedStopGeneration != 0L &&
                            data.internalRestartGeneration == requestedStartGeneration &&
                            data.internalRestartStopGeneration == requestedStopGeneration &&
                            data.startGeneration == requestedStartGeneration &&
                            data.stoppingGeneration == requestedStopGeneration &&
                            !data.hardStopRequested
                    if (!valid) {
                        rejectInternalStart = true
                        terminateRejectedStart = data.hardStopRequested ||
                                data.startGeneration == 0L
                    } else {
                        // Consume the exact restart ticket. Duplicate/stale service intents cannot
                        // start a second connecting generation.
                        data.internalRestartGeneration = 0L
                        data.internalRestartStopGeneration = 0L
                        acceptedStartGeneration = requestedStartGeneration
                        expectedStopGeneration = requestedStopGeneration
                        stopToAwait = data.stoppingDeferred?.takeIf {
                            data.stoppingGeneration == requestedStopGeneration
                        }
                    }
                } else {
                    val stopIsDraining = data.state == State.Stopping ||
                            data.stoppingDeferred?.isCompleted == false
                    if (!stopIsDraining &&
                        (data.state == State.Connected || data.state == State.Connecting)
                    ) {
                        ignoreAlreadyRunning = true
                    } else {
                        data.hardStopRequested = false
                        data.internalRestartGeneration = 0L
                        data.internalRestartStopGeneration = 0L
                        acceptedStartGeneration = data.nextGenerationLocked()
                        data.startGeneration = acceptedStartGeneration
                        expectedStopGeneration = data.stoppingGeneration
                        stopToAwait = data.stoppingDeferred?.takeUnless { it.isCompleted }
                        if (stopIsDraining) {
                            data.restartRequested = true
                            queueForStoppingGeneration = true
                        } else {
                            // State.Stopped may briefly coexist with a completing old Deferred.
                            // This start owns a newer generation and can wait for that exact drain.
                            data.restartRequested = false
                        }
                    }
                }
            }

            // startForegroundService() must be acknowledged immediately, including while an old
            // generation is still draining or when an obsolete internal ticket is delivered.
            // Creation, ownership, and show() are serialized with teardown so destroy() cannot win
            // between publishing the owner and entering the foreground state.
            var notification: ServiceNotification? = null
            var foregroundFailure: Throwable? = null
            var failedNotificationToDestroy: ServiceNotification? = null
            synchronized(data.stoppingLock) {
                try {
                    val currentNotification = data.notification ?: createNotification("").also {
                        data.notification = it
                    }
                    notification = currentNotification
                    if (!currentNotification.show()) {
                        foregroundFailure = IllegalStateException(
                            "Foreground notification was destroyed before it could be shown"
                        )
                    }
                } catch (error: Throwable) {
                    foregroundFailure = error
                }

                if (foregroundFailure != null) {
                    notification?.takeIf { data.notification === it }?.let {
                        data.notification = null
                        failedNotificationToDestroy = it
                    }
                    if (acceptedStartGeneration != 0L &&
                        data.startGeneration == acceptedStartGeneration
                    ) {
                        data.startGeneration = 0L
                        data.restartRequested = false
                        if (data.internalRestartGeneration == acceptedStartGeneration) {
                            data.internalRestartGeneration = 0L
                            data.internalRestartStopGeneration = 0L
                        }
                    }
                }
            }

            foregroundFailure?.let { error ->
                Logs.w("Failed to enter foreground", error)
                try {
                    failedNotificationToDestroy?.destroy()
                } catch (destroyError: Throwable) {
                    Logs.w("Failed to release foreground notification", destroyError)
                }
                (this as Service).stopSelf(startId)
                return Service.START_NOT_STICKY
            }
            val activeNotification = checkNotNull(notification)

            if (ignoreAlreadyRunning) return Service.START_NOT_STICKY
            if (rejectInternalStart) {
                if (terminateRejectedStart) {
                    val notificationToDestroy = synchronized(data.stoppingLock) {
                        if ((data.hardStopRequested || data.startGeneration == 0L) &&
                            data.notification === activeNotification
                        ) {
                            data.notification.also { data.notification = null }
                        } else {
                            null
                        }
                    }
                    try {
                        notificationToDestroy?.destroy()
                    } catch (error: Throwable) {
                        Logs.w("Failed to release foreground notification", error)
                    }
                    (this as Service).stopSelf(startId)
                }
                return Service.START_NOT_STICKY
            }
            if (queueForStoppingGeneration) return Service.START_NOT_STICKY

            val connectingJob = data.binder.launch(start = CoroutineStart.LAZY) {
                val currentJob = checkNotNull(coroutineContext[Job])
                try {
                    stopToAwait?.await()

                    val beganConnecting = synchronized(data.stoppingLock) {
                        val mayConnect = data.connectingJob === currentJob &&
                                data.startGeneration == acceptedStartGeneration &&
                                data.stoppingGeneration == expectedStopGeneration &&
                                data.state == State.Stopped &&
                                data.proxy == null &&
                                data.notification === activeNotification &&
                                data.stoppingDeferred?.isActive != true &&
                                !data.hardStopRequested
                        if (mayConnect) {
                            DataStore.baseService = this@Interface
                            data.changeState(State.Connecting)
                        }
                        mayConnect
                    }
                    if (!beganConnecting) return@launch

                    val selectedProfileId = DataStore.selectedProxy
                    val showGroupInNotification = DataStore.showGroupInNotification

                    val loadedProfile = withContext(Dispatchers.IO) {
                        SagerDatabase.proxyDao.getById(selectedProfileId)?.let { profile ->
                            val groupName = if (showGroupInNotification) {
                                SagerDatabase.groupDao.getById(profile.groupId)?.displayName()
                            } else {
                                null
                            }
                            profile to ServiceNotification.genTitle(profile, groupName)
                        }
                    }

                    var selectedProfileChanged = false
                    val lifecycleIsCurrent = synchronized(data.stoppingLock) {
                        val current = data.connectingJob === currentJob &&
                                data.startGeneration == acceptedStartGeneration &&
                                data.stoppingGeneration == expectedStopGeneration &&
                                data.state == State.Connecting &&
                                data.proxy == null &&
                                data.notification === activeNotification &&
                                data.stoppingDeferred?.isActive != true &&
                                !data.hardStopRequested
                        if (current) {
                            selectedProfileChanged = DataStore.selectedProxy != selectedProfileId
                        }
                        current
                    }
                    if (!lifecycleIsCurrent) return@launch
                    if (selectedProfileChanged) {
                        stopRunner(true)
                        return@launch
                    }
                    if (loadedProfile == null) {
                        stopRunner(false, getString(R.string.profile_empty))
                        return@launch
                    }

                    val (profile, notificationTitle) = loadedProfile
                    val proxy = ProxyInstance(profile, this@Interface, notificationTitle)
                    selectedProfileChanged = false
                    val proxyCommitted = synchronized(data.stoppingLock) {
                        val current = data.connectingJob === currentJob &&
                                data.startGeneration == acceptedStartGeneration &&
                                data.stoppingGeneration == expectedStopGeneration &&
                                data.state == State.Connecting &&
                                data.proxy == null &&
                                data.notification === activeNotification &&
                                data.stoppingDeferred?.isActive != true &&
                                !data.hardStopRequested
                        if (current) {
                            selectedProfileChanged = DataStore.selectedProxy != selectedProfileId
                        }
                        if (!current || selectedProfileChanged) {
                            false
                        } else {
                            data.proxy = proxy
                            check(activeNotification.postNotificationTitle(notificationTitle)) {
                                "Foreground notification was destroyed during startup"
                            }
                            true
                        }
                    }
                    if (!proxyCommitted) {
                        if (selectedProfileChanged) stopRunner(true)
                        return@launch
                    }

                    BootReceiver.enabled = DataStore.persistAcrossReboot
                    if (!data.closeReceiverRegistered) {
                        val filter = IntentFilter().apply {
                            addAction(Action.RELOAD)
                            addAction(Intent.ACTION_SHUTDOWN)
                            addAction(Action.CLOSE)
                            // addAction(Action.SWITCH_WAKE_LOCK)
                            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                            addAction(Action.RESET_UPSTREAM_CONNECTIONS)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            registerReceiver(
                                data.receiver,
                                filter,
                                "$packageName.SERVICE",
                                null,
                                Context.RECEIVER_EXPORTED
                            )
                        } else {
                            registerReceiver(
                                data.receiver,
                                filter,
                                "$packageName.SERVICE",
                                null
                            )
                        }
                        data.closeReceiverRegistered = true
                    }

                    withContext(Dispatchers.IO) {
                        Executable.killAll()    // clean up old processes
                        preInit()
                        proxy.init()
                        DataStore.currentProfile = profile.id
                        AutoRegionManager.apply(this@Interface, profile)

                        proxy.processes = GuardedProcessPool {
                            Logs.w(it)
                            stopRunner(false, it.readableMessage)
                        }

                        startProcesses()
                    }
                    lateInit(
                        currentJob,
                        acceptedStartGeneration,
                        expectedStopGeneration,
                        proxy,
                        activeNotification,
                    )
                } catch (_: CancellationException) { // if the job was cancelled, it is canceller's responsibility to call stopRunner
                } catch (_: UnknownHostException) {
                    stopRunner(false, getString(R.string.invalid_server))
                } catch (e: PluginManager.PluginUntrustedException) {
                    Logs.w(e)
                    data.binder.missingPlugin(e.plugin)
                    stopRunner(false, e.readableMessage)
                } catch (e: PluginManager.PluginNotFoundException) {
                    Toast.makeText(this@Interface, e.readableMessage, Toast.LENGTH_SHORT).show()
                    Logs.w(e)
                    data.binder.missingPlugin(e.plugin)
                    stopRunner(false, null)
                } catch (exc: Throwable) {
                    if (exc.javaClass.name.endsWith("proxyerror")) {
                        // error from golang
                        Logs.w(exc.readableMessage)
                    } else {
                        Logs.w(exc)
                    }
                    stopRunner(
                        false, "${getString(R.string.service_failed)}: ${exc.readableMessage}"
                    )
                } finally {
                    synchronized(data.stoppingLock) {
                        if (data.connectingJob === currentJob) {
                            data.connectingJob = null
                        }
                    }
                }
            }
            synchronized(data.stoppingLock) {
                if (data.startGeneration != acceptedStartGeneration ||
                    data.stoppingGeneration != expectedStopGeneration ||
                    data.state != State.Stopped ||
                    data.notification !== activeNotification ||
                    data.stoppingDeferred?.let { it !== stopToAwait && it.isActive } == true ||
                    data.hardStopRequested
                ) {
                    connectingJob.cancel()
                    return Service.START_NOT_STICKY
                }
                data.connectingJob = connectingJob
            }
            connectingJob.start()
            return Service.START_NOT_STICKY
        }
    }

}
