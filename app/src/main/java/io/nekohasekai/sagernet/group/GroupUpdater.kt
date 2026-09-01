

package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.InetAddress
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

@Suppress("EXPERIMENTAL_API_USAGE")
abstract class GroupUpdater {

    abstract suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    )

    data class Progress(
        var max: Int
    ) {
        var progress by AtomicInteger()
    }

    protected suspend fun forceResolve(
        profiles: List<AbstractBean>, groupId: Long?
    ) {
        val ipv6Mode = DataStore.ipv6Mode
        val lookupJobs = mutableListOf<Job>()
        val progress = Progress(profiles.size)
        if (groupId != null) {
            GroupUpdater.progress[groupId] = progress
            GroupManager.postReload(groupId)
        }
        val ipv6First = ipv6Mode >= IPv6Mode.PREFER

        coroutineScope {
            val lookupDispatcher = Dispatchers.IO.limitedParallelism(5)
            for (profile in profiles) {
                when (profile) {
                    // SNI rewrite unsupported
                    is NaiveBean -> continue
                }

                if (profile.serverAddress.isIpAddress()) continue

                lookupJobs.add(launch(lookupDispatcher) {
                    try {
                        val underlyingNetwork = SagerNet.underlyingNetwork
                        val results = if (
                            underlyingNetwork != null &&
                            DataStore.enableFakeDns &&
                            DataStore.serviceState.started &&
                            DataStore.serviceMode == Key.MODE_VPN
                        ) {
                            // FakeDNS
                            underlyingNetwork.getAllByName(profile.serverAddress)
                                .filterNotNull()
                        } else {
                            // System DNS is enough (when VPN connected, it uses v2ray-core)
                            InetAddress.getAllByName(profile.serverAddress).filterNotNull()
                        }
                        if (results.isEmpty()) error("empty response")
                        rewriteAddress(profile, results, ipv6First)
                    } catch (e: Exception) {
                        Logs.d("Lookup ${profile.serverAddress} failed: ${e.readableMessage}", e)
                    }
                    if (groupId != null) {
                        progress.progress++
                        GroupManager.postReload(groupId)
                    }
                })
            }

            lookupJobs.joinAll()
        }
    }

    protected fun rewriteAddress(
        bean: AbstractBean, addresses: List<InetAddress>, ipv6First: Boolean
    ) {
        val address = addresses.sortedBy { (it is Inet4Address) xor ipv6First }[0].hostAddress

        with(bean) {
            when (this) {
                is HttpBean -> {
                    if (isTLS() && sni.isBlank()) sni = bean.serverAddress
                }
                is StandardV2RayBean -> {
                    when (security) {
                        "tls" -> if (sni.isBlank()) sni = bean.serverAddress
                    }
                }
                is TrojanBean -> {
                    if (sni.isBlank()) sni = bean.serverAddress
                }
                is TrojanGoBean -> {
                    if (sni.isBlank()) sni = bean.serverAddress
                }
                is HysteriaBean -> {
                    if (sni.isBlank()) sni = bean.serverAddress
                }
            }

            bean.serverAddress = address
        }
    }

    companion object {

        val updating = Collections.synchronizedSet<Long>(mutableSetOf())
        val progress = Collections.synchronizedMap<Long, Progress>(mutableMapOf())

        fun startUpdate(proxyGroup: ProxyGroup, byUser: Boolean) {
            runOnDefaultDispatcher {
                executeUpdate(proxyGroup, byUser)
            }
        }

        suspend fun executeUpdate(proxyGroup: ProxyGroup, byUser: Boolean): Boolean {
            return coroutineScope {
                if (!updating.add(proxyGroup.id)) return@coroutineScope false
                GroupManager.postReload(proxyGroup.id)

                try {
                    val subscription = proxyGroup.subscription!!
                    val connected = DataStore.serviceState.connected
                    val userInterface = GroupManager.userInterface

                    if (byUser && (subscription.link?.startsWith("http://") == true || subscription.updateWhenConnectedOnly) && !connected) {
                        if (userInterface == null || !userInterface.confirm(app.getString(R.string.update_subscription_warning))) {
                            return@coroutineScope true
                        }
                    }

                    RawUpdater.doUpdate(proxyGroup, subscription, userInterface, byUser)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Logs.w(e)
                    GroupManager.userInterface?.onUpdateFailure(proxyGroup, e.readableMessage)
                    false
                } finally {
                    finishUpdate(proxyGroup)
                }
            }
        }


        suspend fun finishUpdate(proxyGroup: ProxyGroup) {
            updating.remove(proxyGroup.id)
            progress.remove(proxyGroup.id)
            GroupManager.postUpdate(proxyGroup)
        }

    }

}
