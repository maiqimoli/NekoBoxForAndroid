package io.nekohasekai.sagernet.ui

import android.content.Context
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.plugin.PluginManager
import moe.matsuri.nb4a.Protocols
import java.net.UnknownHostException

object ChainHopTester {

    suspend fun test(
        context: Context,
        chain: ProxyEntity,
        onUpdate: (String) -> Unit,
    ) {
        val hops = resolveChainHops(chain)
        if (hops.isEmpty()) {
            onUpdate(context.getString(R.string.profile_empty))
            return
        }

        val lines = ArrayList<String>()
        val urlTest = UrlTest()
        for ((index, hop) in hops.withIndex()) {
            val ip = resolveProfileIp(hop)
            val singleStatus = chainTestStatus(context, urlTest, isolatedTestProfile(hop))
            val prefixStatus = if (index == 0) {
                context.getString(R.string.chain_hop_same_as_single)
            } else {
                chainTestStatus(context, urlTest, chainHopTestProfile(chain, hops, index))
            }
            lines.add(formatChainHopLine(context, index, hop, ip, singleStatus, prefixStatus))
            onUpdate(
                if (index == hops.lastIndex) {
                    lines.joinToString("\n")
                } else {
                    lines.joinToString("\n") + "\n" +
                            context.getString(R.string.connection_test_testing)
                }
            )
        }
    }

    private fun resolveChainHops(proxyEntity: ProxyEntity): List<ProxyEntity> {
        if (proxyEntity.type != ProxyEntity.TYPE_CHAIN) return emptyList()
        val ids = proxyEntity.chainBean?.proxies ?: return emptyList()
        return resolveChainHops(ids, hashSetOf(proxyEntity.id))
    }

    private fun resolveChainHops(ids: List<Long>, seen: HashSet<Long>): List<ProxyEntity> {
        if (ids.isEmpty()) return emptyList()
        val entities = SagerDatabase.proxyDao.getEntities(ids).associateBy { it.id }
        val result = ArrayList<ProxyEntity>()
        for (id in ids) {
            val entity = entities[id] ?: continue
            if (entity.type == ProxyEntity.TYPE_CHAIN) {
                if (seen.add(entity.id)) {
                    result.addAll(resolveChainHops(entity.chainBean?.proxies ?: emptyList(), seen))
                }
            } else {
                result.add(entity)
            }
        }
        return result
    }

    private fun formatChainHopLine(
        context: Context,
        index: Int,
        profile: ProxyEntity,
        ip: String,
        singleStatus: String,
        prefixStatus: String,
    ): String {
        return context.getString(
            R.string.chain_hop_detail,
            index + 1,
            profile.displayName(),
            profile.displayType(),
            profile.displayAddress(),
            ip,
            singleStatus,
            prefixStatus,
        )
    }

    private fun isolatedTestProfile(profile: ProxyEntity): ProxyEntity {
        return profile.copy(groupId = 0L).putBean(profile.requireBean().clone())
    }

    private fun chainHopTestProfile(chain: ProxyEntity, hops: List<ProxyEntity>, index: Int): ProxyEntity {
        if (index == 0) return isolatedTestProfile(hops[0])
        return ProxyEntity(
            groupId = 0L,
            type = ProxyEntity.TYPE_CHAIN,
            chainBean = ChainBean().apply {
                initializeDefaultValues()
                name = chain.displayName()
                proxies = hops.take(index + 1).map { it.id }
            },
        )
    }

    private fun resolveProfileIp(profile: ProxyEntity): String {
        val address = profile.requireBean().serverAddress
        if (address.isIpAddress()) return address
        return try {
            val resolved = SagerNet.underlyingNetwork?.getAllByName(address)
            resolved?.firstOrNull()?.hostAddress ?: address
        } catch (_: UnknownHostException) {
            address
        }
    }

    private suspend fun chainTestStatus(
        context: Context,
        urlTest: UrlTest,
        profile: ProxyEntity,
    ): String {
        return try {
            val elapsed = urlTest.doTest(profile)
            context.getString(R.string.available, elapsed)
        } catch (e: PluginManager.PluginNotFoundException) {
            e.readableMessage
        } catch (e: Exception) {
            val err = e.readableMessage
            val msg = Protocols.genFriendlyMsg(err)
            if (msg != err) msg else context.getString(R.string.unavailable)
        }
    }
}
