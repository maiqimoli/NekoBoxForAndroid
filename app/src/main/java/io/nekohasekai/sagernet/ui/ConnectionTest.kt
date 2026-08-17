package io.nekohasekai.sagernet.ui

import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.bg.proto.LATENCY_SAMPLE_COUNT
import io.nekohasekai.sagernet.bg.proto.medianLatency
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutProgressListBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.DelicateCoroutinesApi
import moe.matsuri.nb4a.ui.ConnectionTestNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
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
class TestDialog(private val fragment: Fragment) {

    private val context get() = fragment.requireContext()
    private val binding = LayoutProgressListBinding.inflate(fragment.layoutInflater)
    val builder = MaterialAlertDialogBuilder(context).setView(binding.root)
        .setPositiveButton(R.string.minimize) { _, _ ->
            minimize()
        }
        .setNegativeButton(android.R.string.cancel) { _, _ ->
            cancel()
        }
        .setCancelable(false)

    lateinit var cancel: () -> Unit
    lateinit var minimize: () -> Unit

    val dialogStatus = AtomicInteger(0) // 1: hidden 2: cancelled
    var notification: ConnectionTestNotification? = null

    val results: MutableSet<io.nekohasekai.sagernet.database.ProxyEntity> =
        ConcurrentHashMap.newKeySet()
    var proxyN = 0
    val finishedN = AtomicInteger(0)
    private val testingIds = ConcurrentHashMap.newKeySet<Long>()
    val completedIds = ConcurrentHashMap.newKeySet<Long>()

    fun start(profile: io.nekohasekai.sagernet.database.ProxyEntity) {
        testingIds.add(profile.id)
        (fragment as? ConfigurationFragment)?.updateProfileTesting(profile.id, true)
    }

    fun update(
        profile: io.nekohasekai.sagernet.database.ProxyEntity,
        method: ProfileTestMethod,
    ) {
        testingIds.remove(profile.id)
        ProfileUiState.markTestedPending(profile.id, method)
        completedIds.add(profile.id)
        (fragment as? ConfigurationFragment)?.updateProfileTesting(profile.id, false)
        if (dialogStatus.get() != 2) {
            results.add(profile)
        }
        runOnMainDispatcher {
            val ctx = fragment.context ?: return@runOnMainDispatcher
            val progress = finishedN.addAndGet(1)
            val status = dialogStatus.get()
            notification?.updateNotification(
                progress,
                proxyN,
                progress >= proxyN || status == 2
            )
            if (status >= 1) return@runOnMainDispatcher
            if (!fragment.isAdded) return@runOnMainDispatcher

            // refresh dialog

            var profileStatusText: String? = null
            var profileStatusColor = 0

            when (profile.status) {
                -1 -> {
                    profileStatusText = profile.error
                    profileStatusColor = ctx.getColorAttr(android.R.attr.textColorSecondary)
                }

                0 -> {
                    profileStatusText = fragment.getString(R.string.connection_test_testing)
                    profileStatusColor = ctx.getColorAttr(android.R.attr.textColorSecondary)
                }

                1 -> {
                    profileStatusText = fragment.getString(R.string.available, profile.ping)
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
                        if (msg != err) msg else fragment.getString(R.string.unavailable)
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

            binding.nowTesting.text = text
            binding.progress.text = "$progress / $proxyN"
        }
    }

    fun clearTesting() {
        val owner = fragment as? ConfigurationFragment ?: return
        val ids = testingIds.toList()
        ids.forEach { owner.updateProfileTesting(it, false) }
        ids.forEach(testingIds::remove)
    }

}

@OptIn(DelicateCoroutinesApi::class)
@Suppress("EXPERIMENTAL_API_USAGE")
fun ConfigurationFragment.pingTestImpl(fragment: ConfigurationFragment, icmpPing: Boolean) {
    if (DataStore.runningTest) return else DataStore.runningTest = true
    val test = TestDialog(fragment)
    val dialog = test.builder.show()
    val testJobs = mutableListOf<Job>()
    val group = DataStore.currentGroup()

    val mainJob = GlobalScope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
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
                    test.start(profile)
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
                            profile.error = fragment.getString(R.string.connection_test_unreachable)
                        } else {
                            profile.status = 2
                            when {
                                !message.contains("failed:") -> profile.error =
                                    fragment.getString(R.string.connection_test_timeout)

                                else -> when {
                                    message.contains("ECONNREFUSED") -> {
                                        profile.error =
                                            fragment.getString(R.string.connection_test_refused)
                                    }

                                    message.contains("ENETUNREACH") -> {
                                        profile.error =
                                            fragment.getString(R.string.connection_test_unreachable)
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

        runOnMainDispatcher {
            test.cancel()
        }
    }
    test.cancel = cancel@{
        if (test.dialogStatus.getAndSet(2) == 2) return@cancel
        mainJob.cancel()
        dialog.dismiss()
        runOnDefaultDispatcher {
            mainJob.join()
            onMainDispatcher {
                test.clearTesting()
            }
            ProfileUiState.flushTested(test.completedIds)
            test.results.forEach {
                try {
                    ProfileManager.updateProfile(it)
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }
            GroupManager.postReload(DataStore.currentGroupId())
            DataStore.runningTest = false
        }
    }
    test.minimize = minimize@{
        if (!test.dialogStatus.compareAndSet(0, 1)) return@minimize
        test.notification = ConnectionTestNotification(
            dialog.context,
            "[${group.displayName()}] ${fragment.getString(R.string.connection_test)}"
        )
        dialog.hide()
    }
    mainJob.invokeOnCompletion { cause ->
        if (cause != null && test.dialogStatus.get() != 2) {
            runOnMainDispatcher { test.cancel() }
        }
    }
    mainJob.start()
}

@OptIn(DelicateCoroutinesApi::class)
fun ConfigurationFragment.urlTestImpl(
    fragment: ConfigurationFragment,
    profileIds: Set<Long>? = null,
) {
    if (DataStore.runningTest) return else DataStore.runningTest = true
    val test = TestDialog(fragment)
    val dialog = test.builder.show()
    val testJobs = mutableListOf<Job>()
    val group = DataStore.currentGroup()

    val mainJob = GlobalScope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
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
                    test.start(profile)

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

                    test.update(profile, ProfileTestMethod.URL)
                }
            })
        }

        testJobs.joinAll()

        runOnMainDispatcher {
            test.cancel()
        }
    }
    test.cancel = cancel@{
        if (test.dialogStatus.getAndSet(2) == 2) return@cancel
        mainJob.cancel()
        dialog.dismiss()
        runOnDefaultDispatcher {
            mainJob.join()
            onMainDispatcher {
                test.clearTesting()
            }
            ProfileUiState.flushTested(test.completedIds)
            test.results.forEach {
                try {
                    ProfileManager.updateProfile(it)
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }
            GroupManager.postReload(DataStore.currentGroupId())
            DataStore.runningTest = false
        }
    }
    test.minimize = minimize@{
        if (!test.dialogStatus.compareAndSet(0, 1)) return@minimize
        test.notification = ConnectionTestNotification(
            dialog.context,
            "[${group.displayName()}] ${fragment.getString(R.string.connection_test)}"
        )
        dialog.hide()
    }
    mainJob.invokeOnCompletion { cause ->
        if (cause != null && test.dialogStatus.get() != 2) {
            runOnMainDispatcher { test.cancel() }
        }
    }
    mainJob.start()
}
