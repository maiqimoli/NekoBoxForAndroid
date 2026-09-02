package io.nekohasekai.sagernet.ui

import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.bg.proto.LATENCY_SAMPLE_COUNT
import io.nekohasekai.sagernet.bg.proto.medianLatency
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutProgressListBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import moe.matsuri.nb4a.ui.ConnectionTestNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor

internal fun connectionTestWorkerCount(
    profileCount: Int,
    requested: Int,
    containsChain: Boolean,
): Int {
    if (profileCount <= 0) return 0
    val maximum = if (containsChain) 2 else 8
    return requested.coerceIn(1, maximum).coerceAtMost(profileCount)
}

/**
 * 连接测试进度对话框。抽取自 ConfigurationFragment 以控制单文件规模。
 */
internal class TestDialog(
    fragment: Fragment,
    val token: ConnectionTestToken,
) : DefaultLifecycleObserver {

    @Volatile
    private var fragment: Fragment? = fragment
    private var binding: LayoutProgressListBinding? =
        LayoutProgressListBinding.inflate(fragment.layoutInflater)
    private var dialog: AlertDialog? = null
    private var observedLifecycle: Lifecycle? = null

    lateinit var cancel: () -> Unit
    lateinit var minimize: () -> Unit

    val dialogStatus = AtomicInteger(0) // 1: hidden 2: cancelled
    @Volatile
    var notification: ConnectionTestNotification? = null

    val results = ConcurrentHashMap<Long, CompletedConnectionTest>()
    @Volatile
    var proxyN = 0
    val finishedN = AtomicInteger(0)

    fun show() {
        val owner = fragment ?: return
        val currentBinding = binding ?: return
        dialog = MaterialAlertDialogBuilder(owner.requireContext())
            .setView(currentBinding.root)
            .setPositiveButton(R.string.minimize) { _, _ ->
                minimize()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                cancel()
            }
            .setCancelable(false)
            .show()
    }

    fun observeViewLifecycle() {
        val owner = fragment ?: return
        owner.viewLifecycleOwner.lifecycle.also { lifecycle ->
            observedLifecycle = lifecycle
            lifecycle.addObserver(this)
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        if (dialogStatus.get() == 0) cancel()
        detachUi()
    }

    fun hide() {
        dialog?.hide()
    }

    fun dismissDialog() {
        dialog?.dismiss()
        dialog = null
        binding = null
    }

    fun detachUi() {
        val owner = fragment
        val testingIds = ConnectionTestCoordinator.shared.detachUi(token)
        if (owner is ConfigurationFragment) {
            testingIds.forEach(owner::notifyProfileTestingChanged)
        }
        observedLifecycle?.removeObserver(this)
        observedLifecycle = null
        dismissDialog()
        fragment = null
    }

    fun notifyProfileTestingChanged(profileIds: Collection<Long>) {
        val owner = fragment as? ConfigurationFragment ?: return
        profileIds.forEach(owner::notifyProfileTestingChanged)
    }

    fun start(profile: ProxyEntity): Boolean {
        return when (
            ConnectionTestCoordinator.shared.profileStarted(token, profile.id)
        ) {
            ConnectionTestStartDecision.REJECTED -> false
            ConnectionTestStartDecision.ACCEPTED_HIDDEN -> true
            ConnectionTestStartDecision.ACCEPTED_VISIBLE -> {
                (fragment as? ConfigurationFragment)?.notifyProfileTestingChanged(profile.id)
                true
            }
        }
    }

    fun update(
        profile: ProxyEntity,
        method: ProfileTestMethod,
    ): Boolean {
        val completion = ConnectionTestCoordinator.shared.profileCompleted(token, profile.id)
        val completedAt = completion.acceptedAtSeconds ?: return false
        results[profile.id] = CompletedConnectionTest(
            profileId = profile.id,
            status = profile.status,
            ping = profile.ping,
            error = profile.error,
            record = ProfileTestRecord(completedAt, method),
        )
        if (completion.testingStateChanged) {
            (fragment as? ConfigurationFragment)?.notifyProfileTestingChanged(profile.id)
        }
        val progress = finishedN.addAndGet(1)
        runOnMainDispatcher {
            val status = dialogStatus.get()
            notification?.updateNotification(
                progress,
                proxyN,
                progress >= proxyN || status == 2
            )
            if (status >= 1) return@runOnMainDispatcher
            val owner = fragment ?: return@runOnMainDispatcher
            val currentBinding = binding ?: return@runOnMainDispatcher
            val ctx = owner.context ?: return@runOnMainDispatcher
            if (!owner.isAdded) return@runOnMainDispatcher

            // refresh dialog

            var profileStatusText: String? = null
            var profileStatusColor = 0

            when (profile.status) {
                -1 -> {
                    profileStatusText = profile.error
                    profileStatusColor = ctx.getColorAttr(android.R.attr.textColorSecondary)
                }

                0 -> {
                    profileStatusText = owner.getString(R.string.connection_test_testing)
                    profileStatusColor = ctx.getColorAttr(android.R.attr.textColorSecondary)
                }

                1 -> {
                    profileStatusText = owner.getString(R.string.available, profile.ping)
                    profileStatusColor = ctx.getColour(R.color.material_green_500)
                }

                2 -> {
                    profileStatusText = profile.error
                    profileStatusColor = ctx.getColour(R.color.material_red_500)
                }

                3 -> {
                    val err = profile.error ?: ""
                    val msg = Protocols.genFriendlyMsg(err)
                    profileStatusText =
                        if (msg != err) msg else owner.getString(R.string.unavailable)
                    profileStatusColor = ctx.getColour(R.color.material_red_500)
                }
            }

            val text = SpannableStringBuilder().apply {
                append("\n" + profile.displayName())
                append("\n")
                append(
                    profile.displayType(),
                    ForegroundColorSpan(ctx.getProtocolColor(profile.type)),
                    SPAN_EXCLUSIVE_EXCLUSIVE
                )
                append(" ")
                append(
                    profileStatusText,
                    ForegroundColorSpan(profileStatusColor),
                    SPAN_EXCLUSIVE_EXCLUSIVE
                )
                append("\n")
            }

            currentBinding.nowTesting.text = text
            currentBinding.progress.text = "$progress / $proxyN"
        }
        return true
    }

    fun finishNotification() {
        notification?.updateNotification(finishedN.get(), proxyN, true)
        notification = null
    }

}

private fun finishConnectionTest(
    test: TestDialog,
    mainJob: Job,
    group: ProxyGroup,
) {
    val coordinator = ConnectionTestCoordinator.shared
    val testingIds = coordinator.beginFinish(test.token) ?: return
    test.dialogStatus.set(2)
    test.notifyProfileTestingChanged(testingIds)
    mainJob.cancel()
    test.dismissDialog()
    SagerNet.application.applicationScope.launch(Dispatchers.Default) {
        try {
            mainJob.join()
            val persistedRecords = persistConnectionTestResults(
                results = test.results.values,
                write = { result ->
                    SagerDatabase.proxyDao.updateConnectionTestResult(
                        profileId = result.profileId,
                        status = result.status,
                        ping = result.ping,
                        error = result.error,
                    )
                },
                onFailure = { _, error -> Logs.w(error) },
            )
            runCatching {
                ProfileUiState.recordPersistedTests(persistedRecords)
            }.onFailure { Logs.w(it) }
            persistedRecords.keys.forEach { profileId ->
                runCatching {
                    // Reload the current row before notifying listeners. Tests run against a
                    // snapshot that may predate an edit or drag operation.
                    ProfileManager.postUpdate(profileId)
                }.onFailure { Logs.w(it) }
            }
            runCatching {
                GroupManager.postReload(group.id)
            }.onFailure { Logs.w(it) }
        } finally {
            runCatching { test.finishNotification() }.onFailure { Logs.w(it) }
            runCatching {
                onMainDispatcher { test.detachUi() }
            }.onFailure { Logs.w(it) }
            coordinator.endFinish(test.token)
        }
    }
}

fun ConfigurationFragment.pingTestImpl(
    fragment: ConfigurationFragment,
    icmpPing: Boolean,
): Boolean {
    val coordinator = ConnectionTestCoordinator.shared
    val token = coordinator.tryBegin() ?: return false
    fragment.viewLifecycleOwner.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
        var started = false
        try {
            val group = DataStore.currentGroup()
            startPingTest(fragment, icmpPing, group, token)
            started = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logs.w(e)
            (fragment.activity as? MainActivity)?.snackbar(e.readableMessage)?.show()
        } finally {
            if (!started) coordinator.endFinish(token)
        }
    }
    return true
}

private fun startPingTest(
    fragment: ConfigurationFragment,
    icmpPing: Boolean,
    group: ProxyGroup,
    token: ConnectionTestToken,
) {
    val test = TestDialog(fragment, token)
    test.show()
    val testJobs = mutableListOf<Job>()

    val mainJob = SagerNet.application.applicationScope.launch(
        Dispatchers.Default, start = CoroutineStart.LAZY
    ) {
        val profilesList = SagerDatabase.proxyDao.getByGroup(group.id).filter {
            if (icmpPing) {
                if (it.requireBean().canICMPing()) {
                    return@filter true
                }
            } else {
                if (it.requireBean().canTCPing()) {
                    return@filter true
                }
            }
            return@filter false
        }
        test.proxyN = profilesList.size
        val profiles = ConcurrentLinkedQueue(profilesList)
        val workerCount = connectionTestWorkerCount(
            profilesList.size,
            DataStore.connectionTestConcurrent,
            profilesList.any { it.type == ProxyEntity.TYPE_CHAIN },
        )
        repeat(workerCount) {
            testJobs.add(launch(Dispatchers.IO) {
                while (isActive) {
                    val profile = profiles.poll() ?: break

                    profile.status = 0
                    profile.error = null
                    if (!test.start(profile)) break
                    var address = profile.requireBean().serverAddress
                    if (!address.isIpAddress()) {
                        try {
                            (SagerNet.underlyingNetwork?.getAllByName(address)
                                ?: InetAddress.getAllByName(address)).apply {
                                if (isNotEmpty()) {
                                    address = this[0].hostAddress
                                }
                            }
                        } catch (e: UnknownHostException) {
                            Logs.w(e)
                        }
                    }
                    if (!isActive) break
                    if (!address.isIpAddress()) {
                        profile.status = 2
                        profile.error = app.getString(R.string.connection_test_domain_not_found)
                        test.update(profile, ProfileTestMethod.TCP)
                        continue
                    }
                    try {
                        if (icmpPing) {
                            // removed
                        } else {
                            val samples = ArrayList<Int>(LATENCY_SAMPLE_COUNT)
                            for (sampleIndex in 0 until LATENCY_SAMPLE_COUNT) {
                                val socket =
                                    SagerNet.underlyingNetwork?.socketFactory?.createSocket()
                                        ?: Socket()
                                try {
                                    socket.soTimeout = 3000
                                    socket.bind(InetSocketAddress(0))
                                    val start = SystemClock.elapsedRealtime()
                                    socket.connect(
                                        InetSocketAddress(
                                            address, profile.requireBean().serverPort
                                        ), 3000
                                    )
                                    samples += (SystemClock.elapsedRealtime() - start).toInt()
                                } finally {
                                    socket.closeQuietly()
                                }
                                if (!isActive) break
                                if (sampleIndex < LATENCY_SAMPLE_COUNT - 1) {
                                    kotlinx.coroutines.delay(75L)
                                }
                            }
                            if (!isActive) break
                            profile.status = 1
                            profile.ping = medianLatency(samples)
                            test.update(profile, ProfileTestMethod.TCP)
                        }
                    } catch (e: Exception) {
                        if (!isActive) break
                        val message = e.readableMessage

                        if (icmpPing) {
                            profile.status = 2
                            profile.error = app.getString(R.string.connection_test_unreachable)
                        } else {
                            profile.status = 2
                            when {
                                !message.contains("failed:") -> profile.error =
                                    app.getString(R.string.connection_test_timeout)

                                else -> when {
                                    message.contains("ECONNREFUSED") -> {
                                        profile.error =
                                            app.getString(R.string.connection_test_refused)
                                    }

                                    message.contains("ENETUNREACH") -> {
                                        profile.error =
                                            app.getString(R.string.connection_test_unreachable)
                                    }

                                    else -> {
                                        profile.status = 3
                                        profile.error = message
                                    }
                                }
                            }
                        }
                        test.update(profile, ProfileTestMethod.TCP)
                    }
                }
            })
        }

        testJobs.joinAll()
    }
    test.cancel = cancel@{
        finishConnectionTest(test, mainJob, group)
    }
    test.minimize = minimize@{
        if (!test.dialogStatus.compareAndSet(0, 1)) return@minimize
        test.notification = ConnectionTestNotification(
            SagerNet.application,
            "[${group.displayName()}] ${SagerNet.application.getString(R.string.connection_test)}"
        )
        test.hide()
    }
    test.observeViewLifecycle()
    mainJob.invokeOnCompletion {
        runOnMainDispatcher { test.cancel() }
    }
    mainJob.start()
}

fun ConfigurationFragment.urlTestImpl(
    fragment: ConfigurationFragment,
    profileIds: Set<Long>? = null,
): Boolean {
    val coordinator = ConnectionTestCoordinator.shared
    val token = coordinator.tryBegin() ?: return false
    fragment.viewLifecycleOwner.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
        var started = false
        try {
            val group = DataStore.currentGroup()
            startUrlTest(fragment, profileIds, group, token)
            started = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logs.w(e)
            (fragment.activity as? MainActivity)?.snackbar(e.readableMessage)?.show()
        } finally {
            if (!started) coordinator.endFinish(token)
        }
    }
    return true
}

private fun startUrlTest(
    fragment: ConfigurationFragment,
    profileIds: Set<Long>?,
    group: ProxyGroup,
    token: ConnectionTestToken,
) {
    val test = TestDialog(fragment, token)
    test.show()
    val testJobs = mutableListOf<Job>()

    val mainJob = SagerNet.application.applicationScope.launch(
        Dispatchers.Default, start = CoroutineStart.LAZY
    ) {
        val profilesList = SagerDatabase.proxyDao.getByGroup(group.id).filter {
            profileIds == null || it.id in profileIds
        }
        test.proxyN = profilesList.size
        val profiles = ConcurrentLinkedQueue(profilesList)
        val workerCount = connectionTestWorkerCount(
            profilesList.size,
            DataStore.connectionTestConcurrent,
            profilesList.any { it.type == ProxyEntity.TYPE_CHAIN },
        )
        repeat(workerCount) {
            testJobs.add(launch(Dispatchers.IO) {
                val urlTest = UrlTest() // note: this is NOT in bg process
                while (isActive) {
                    val profile = profiles.poll() ?: break
                    profile.status = 0
                    profile.error = null
                    if (!test.start(profile)) break

                    try {
                        val result = urlTest.doTest(profile)
                        profile.status = 1
                        profile.ping = result
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: PluginManager.PluginNotFoundException) {
                        profile.status = 2
                        profile.error = e.readableMessage
                    } catch (e: Exception) {
                        profile.status = 3
                        profile.error = e.readableMessage
                    }

                    // UrlTest may be a blocking native call and return only after cancellation.
                    // Do not publish a stale result or mark it as freshly tested in that case.
                    coroutineContext.ensureActive()
                    test.update(profile, ProfileTestMethod.URL)
                }
            })
        }

        testJobs.joinAll()
    }
    test.cancel = cancel@{
        finishConnectionTest(test, mainJob, group)
    }
    test.minimize = minimize@{
        if (!test.dialogStatus.compareAndSet(0, 1)) return@minimize
        test.notification = ConnectionTestNotification(
            SagerNet.application,
            "[${group.displayName()}] ${SagerNet.application.getString(R.string.connection_test)}"
        )
        test.hide()
    }
    test.observeViewLifecycle()
    mainJob.invokeOnCompletion {
        runOnMainDispatcher { test.cancel() }
    }
    mainJob.start()
}
