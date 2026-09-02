package io.nekohasekai.sagernet.group

import android.annotation.SuppressLint
import androidx.core.net.toUri
import androidx.room.withTransaction
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteriaConfig
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteriaJson
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.parseTrojanGo
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.fmt.v2ray.setTLS
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.CancellationException
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.utils.Util
import org.ini4j.Ini
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.TypeDescription
import org.yaml.snakeyaml.Yaml
import java.io.StringReader

@Suppress("EXPERIMENTAL_API_USAGE")
object RawUpdater : GroupUpdater() {

    private val HYSTERIA_SERVER_LINE = Regex("""(?m)^\s*server\s*:""")

    private fun subscriptionYaml(): Yaml {
        val options = LoaderOptions().apply {
            codePointLimit = MAX_SUBSCRIPTION_DECODED_BYTES
            maxAliasesForCollections = 50
            nestingDepthLimit = MAX_SUBSCRIPTION_NESTING_DEPTH
        }
        return Yaml(options).apply {
            addTypeDescription(TypeDescription(String::class.java, "str"))
        }
    }

    internal fun loadSubscriptionYamlMap(
        text: String,
        eventBudget: SubscriptionParseBudget = SubscriptionParseBudget(),
        decodedBudget: SubscriptionParseBudget = SubscriptionParseBudget(),
    ): Map<*, *> {
        val yaml = subscriptionYaml()
        eventBudget.validateYamlEvents(yaml.parse(StringReader(text)))
        val decoded = yaml.loadAs(text, Map::class.java) ?: error("Empty YAML subscription")
        decodedBudget.validateDecodedTree(decoded)
        return decoded
    }

    private class BudgetedJsonTokener(
        text: String,
        private val budget: SubscriptionParseBudget,
    ) : JSONTokener(text) {

        override fun nextValue(): Any {
            budget.recordNode()
            return super.nextValue()
        }
    }

    internal fun parseJsonValueWithBudget(
        text: String,
        budget: SubscriptionParseBudget = SubscriptionParseBudget(),
    ): Any = BudgetedJsonTokener(text, budget).nextValue()

    private fun Throwable.isSubscriptionYamlLimit(): Boolean {
        var error: Throwable? = this
        while (error != null) {
            val message = error.message.orEmpty().lowercase()
            if ("exceeds the limit" in message ||
                "exceeded max" in message ||
                "aliases for non-scalar nodes" in message
            ) {
                return true
            }
            error = error.cause
        }
        return false
    }

    @SuppressLint("Recycle")
    override suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    ) {

        val link = subscription.link
        var proxies: List<AbstractBean>
        if (link.startsWith("content://")) {
            val contentText = app.contentResolver.openInputStream(link.toUri())?.use {
                it.readTextLimited()
            }

            proxies = contentText?.let { parseRaw(contentText) }
                ?: error(app.getString(R.string.no_proxies_found_in_subscription))
        } else {
            requireSecureSubscriptionUrl(link)
            val httpClient = Libcore.newHttpClient().apply {
                trySocks5(DataStore.mixedPort)
                tryH3Direct()
                when (DataStore.appTLSVersion) {
                    "1.3" -> restrictedTLS()
                }
            }
            try {
                val response = httpClient.newRequest().apply {
                    if (DataStore.allowInsecureOnRequest) {
                        allowInsecure()
                    }
                    setURL(link)
                    setUserAgent(
                        subscription.customUserAgent.takeIf { it.isNotBlank() } ?: USER_AGENT,
                    )
                }.execute()
                val responseText = Util.getStringBox(response.contentString)
                    .requireUtf8SizeAtMost(MAX_IMPORTED_CONFIG_BYTES)
                proxies = parseRaw(responseText)
                    ?: error(app.getString(R.string.no_proxies_found))

                subscription.subscriptionUserinfo =
                    Util.getStringBox(response.getHeader("Subscription-Userinfo"))

                // 修改默认名字
                if (proxyGroup.name?.startsWith("Subscription #") == true) {
                    var remoteName = Util.getStringBox(response.getHeader("content-disposition"))
                    if (remoteName.isNotBlank()) {
                        remoteName = Util.decodeFilename(remoteName)
                        if (remoteName.isNotBlank()) {
                            proxyGroup.name = remoteName
                        }
                    }
                }
            } finally {
                httpClient.close()
            }
        }

        ensureUniqueNames(
            items = proxies,
            nameSelector = AbstractBean::displayName,
            rename = { bean, name -> bean.name = name },
        )

        if (subscription.forceResolve) forceResolve(proxies, proxyGroup.id)

        val exists = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
        val duplicate = ArrayList<String>()
        if (subscription.deduplication) {
            Logs.d("Before deduplication: ${proxies.size}")
            val result = deduplicateKeepingFirst(
                items = proxies,
                keySelector = { bean ->
                    Protocols.Deduplication(bean, bean.javaClass.toString())
                },
                nameSelector = AbstractBean::displayName,
            )
            proxies = result.unique
            duplicate.addAll(result.duplicateNames)
        }

        Logs.d("New profiles: ${proxies.size}")

        val nameMap = proxies.associateByTo(LinkedHashMap(proxies.size)) { bean ->
            bean.displayName()
        }

        Logs.d("Unique profiles: ${nameMap.size}")

        val toReplace = LinkedHashMap<String, ProxyEntity>()
        val matchedEntityIds = HashSet<Long>()
        exists.forEach { entity ->
            val name = entity.displayName()
            if (nameMap.containsKey(name) && !toReplace.containsKey(name)) {
                toReplace[name] = entity
                matchedEntityIds.add(entity.id)
            }
        }

        val remainingExisting = exists.filter { it.id !in matchedEntityIds }
        val remainingIncoming = nameMap.entries.filter { it.key !in toReplace }
        val renamedMatches = matchUniqueByKey(
            existing = remainingExisting,
            incoming = remainingIncoming,
            existingKey = { entity -> ProfileIdentity(entity.requireBean()) },
            incomingKey = { entry -> ProfileIdentity(entry.value) },
        )
        for ((entity, entry) in renamedMatches) {
            toReplace[entry.key] = entity
            matchedEntityIds.add(entity.id)
        }

        val toDelete = exists.filterTo(ArrayList()) { it.id !in matchedEntityIds }

        Logs.d("toDelete profiles: ${toDelete.size}")
        Logs.d("toReplace profiles: ${toReplace.size}")

        val toInsert = ArrayList<ProxyEntity>()
        val toUpdate = ArrayList<ProxyEntity>()
        val added = mutableListOf<String>()
        val updated = mutableMapOf<String, String>()
        val deleted = toDelete.map { it.displayName() }

        var userOrder = 1L
        var changed = toDelete.size
        for ((name, bean) in nameMap.entries) {
            val entity = toReplace[name]
            if (entity != null) {
                val existsBean = entity.requireBean()
                val oldName = entity.displayName()
                // 更新订阅，保留自定义覆写设置
                bean.customOutboundJson = existsBean.customOutboundJson
                bean.customConfigJson = existsBean.customConfigJson
                val profileChanged = oldName != name || existsBean != bean
                val orderChanged = entity.userOrder != userOrder
                when {
                    profileChanged || orderChanged -> {
                        entity.putBean(bean)
                        entity.userOrder = userOrder
                        toUpdate.add(entity)

                        if (profileChanged) {
                            changed++
                            updated[oldName] = name

                            Logs.d("Updated profile: $name")
                        } else {
                            Logs.d("Reordered profile: $name")
                        }
                    }

                    else -> {
                        Logs.d("Ignored profile: $name")
                    }
                }
            } else {
                changed++
                toInsert.add(
                    ProxyEntity(
                        groupId = proxyGroup.id, userOrder = userOrder
                    ).apply {
                        putBean(bean)
                    }
                )
                added.add(name)
                Logs.d("Inserted profile: $name")
            }
            userOrder++
        }

        subscription.lastUpdated = (System.currentTimeMillis() / 1000).toInt()
        val (updatedCount, deletedCount, existCount) = SagerDatabase.instance.withTransaction {
            if (toInsert.isNotEmpty()) SagerDatabase.proxyDao.insert(toInsert)
            val updatedCount = if (toUpdate.isEmpty()) {
                0
            } else {
                SagerDatabase.proxyDao.updateProxy(toUpdate)
            }
            val deletedCount = if (toDelete.isEmpty()) {
                0
            } else {
                SagerDatabase.proxyDao.deleteProxy(toDelete)
            }
            SagerDatabase.groupDao.updateGroup(proxyGroup)
            Triple(
                updatedCount,
                deletedCount,
                SagerDatabase.proxyDao.countByGroup(proxyGroup.id).toInt(),
            )
        }
        Logs.d("Inserted profiles: ${toInsert.size}")
        Logs.d("Updated profiles: $updatedCount")
        Logs.d("Deleted profiles: $deletedCount")
        if (existCount != proxies.size) {
            Logs.e("Exist profiles: $existCount, new profiles: ${proxies.size}")
        }

        userInterface?.onUpdateSuccess(
            proxyGroup, changed, added, updated, deleted, duplicate, byUser
        )
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun parseRaw(text: String, fileName: String = ""): List<AbstractBean>? {

        text.requireUtf8SizeAtMost(MAX_IMPORTED_CONFIG_BYTES)

        val proxies = mutableListOf<AbstractBean>()

        if (text.contains("proxies:")) {

            // clash & meta

            try {

                val budget = SubscriptionParseBudget()
                val yaml = loadSubscriptionYamlMap(text)

                val globalClientFingerprint = yaml["global-client-fingerprint"]?.toString() ?: ""

                val profileConfigs = yaml["proxies"] as? List<Map<String, Any?>> ?: error(
                    app.getString(R.string.no_proxies_found_in_file)
                )
                budget.requireProfileCount(profileConfigs.size)

                for (proxy in profileConfigs) {
                    // Note: YAML numbers parsed as "Long"

                    when (proxy["type"] as String) {
                        "socks5" -> {
                            proxies.add(SOCKSBean().apply {
                                serverAddress = proxy["server"] as String
                                serverPort = proxy["port"].toString().toInt()
                                username = proxy["username"]?.toString()
                                password = proxy["password"]?.toString()
                                name = proxy["name"]?.toString()
                            })
                        }

                        "http" -> {
                            proxies.add(HttpBean().apply {
                                serverAddress = proxy["server"] as String
                                serverPort = proxy["port"].toString().toInt()
                                username = proxy["username"]?.toString()
                                password = proxy["password"]?.toString()
                                setTLS(proxy["tls"]?.toString() == "true")
                                sni = proxy["sni"]?.toString()
                                name = proxy["name"]?.toString()
                                allowInsecure = proxy["skip-cert-verify"]?.toString() == "true"
                            })
                        }

                        "ss" -> {
                            val ssPlugin = mutableListOf<String>()
                            if (proxy.contains("plugin")) {
                                val opts = proxy["plugin-opts"] as Map<String, Any?>
                                when (proxy["plugin"]) {
                                    "obfs" -> {
                                        ssPlugin.apply {
                                            add("obfs-local")
                                            add("obfs=" + (opts["mode"]?.toString() ?: ""))
                                            add("obfs-host=" + (opts["host"]?.toString() ?: ""))
                                        }
                                    }

                                    "v2ray-plugin" -> {
                                        ssPlugin.apply {
                                            add("v2ray-plugin")
                                            add("mode=" + (opts["mode"]?.toString() ?: ""))
                                            if (opts["mode"]?.toString() == "true") add("tls")
                                            add("host=" + (opts["host"]?.toString() ?: ""))
                                            add("path=" + (opts["path"]?.toString() ?: ""))
                                            if (opts["mux"]?.toString() == "true") add("mux=8")
                                        }
                                    }
                                }
                            }
                            proxies.add(ShadowsocksBean().apply {
                                serverAddress = proxy["server"] as String
                                serverPort = proxy["port"].toString().toInt()
                                password = proxy["password"]?.toString()
                                method = clashCipher(proxy["cipher"] as String)
                                plugin = ssPlugin.joinToString(";")
                                name = proxy["name"]?.toString()
                            })
                        }

                        "vmess", "vless", "trojan" -> {
                            val bean = when (proxy["type"] as String) {
                                "vmess" -> VMessBean()
                                "vless" -> VMessBean().apply {
                                    alterId = -1 // make it VLESS
                                    packetEncoding = 2 // clash meta default XUDP
                                }

                                "trojan" -> TrojanBean().apply {
                                    security = "tls"
                                }

                                else -> error("impossible")
                            }

                            bean.serverAddress = proxy["server"]?.toString() ?: continue
                            bean.serverPort = proxy["port"]?.toString()?.toIntOrNull() ?: continue

                            for (opt in proxy) {
                                when (opt.key) {
                                    "name" -> bean.name = opt.value?.toString()
                                    "password" -> if (bean is TrojanBean) bean.password =
                                        opt.value?.toString()

                                    "uuid" -> if (bean is VMessBean) bean.uuid =
                                        opt.value?.toString()

                                    "alterId" -> if (bean is VMessBean && !bean.isVLESS) bean.alterId =
                                        opt.value?.toString()?.toIntOrNull()

                                    "cipher" -> if (bean is VMessBean && !bean.isVLESS) bean.encryption =
                                        (opt.value as? String)

                                    "flow" -> if (bean is VMessBean && bean.isVLESS) {
                                        (opt.value as? String)?.let {
                                            if (it.contains("xtls-rprx-vision")) {
                                                bean.encryption = "xtls-rprx-vision"
                                            }
                                        }
                                    }

                                    "packet-encoding" -> if (bean is VMessBean) {
                                        bean.packetEncoding = when ((opt.value as? String)) {
                                            "packetaddr" -> 1
                                            "xudp" -> 2
                                            else -> 0
                                        }
                                    }

                                    "tls" -> if (bean is VMessBean) {
                                        bean.security =
                                            if (opt.value as? Boolean == true) "tls" else ""
                                    }

                                    "servername", "sni" -> bean.sni = opt.value?.toString()

                                    "alpn" -> bean.alpn =
                                        (opt.value as? List<Any>)?.joinToString("\n")

                                    "skip-cert-verify" -> bean.allowInsecure =
                                        opt.value as? Boolean == true

                                    "client-fingerprint" -> bean.utlsFingerprint =
                                        opt.value as String

                                    "reality-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (realityOpt in it) {
                                            bean.security = "tls"

                                            when (realityOpt.key) {
                                                "public-key" -> bean.realityPubKey =
                                                    realityOpt.value?.toString()

                                                "short-id" -> bean.realityShortId =
                                                    realityOpt.value?.toString()
                                            }
                                        }
                                    }

                                    "network" -> {
                                        when (opt.value) {
                                            "h2", "http" -> bean.type = "http"
                                            "ws", "grpc" -> bean.type = opt.value as String
                                        }
                                    }

                                    "ws-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (wsOpt in it) {
                                            when (wsOpt.key) {
                                                "headers" -> (wsOpt.value as? Map<Any, Any?>)?.forEach { (key, value) ->
                                                    when (key.toString().lowercase()) {
                                                        "host" -> {
                                                            bean.host = value?.toString()
                                                        }
                                                    }
                                                }

                                                "path" -> {
                                                    bean.path = wsOpt.value?.toString()
                                                }

                                                "max-early-data" -> {
                                                    bean.wsMaxEarlyData =
                                                        wsOpt.value?.toString()?.toIntOrNull()
                                                }

                                                "early-data-header-name" -> {
                                                    bean.earlyDataHeaderName =
                                                        wsOpt.value?.toString()
                                                }

                                                "v2ray-http-upgrade" -> {
                                                    if (wsOpt.value as? Boolean == true) {
                                                        bean.type = "httpupgrade"
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    "h2-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (h2Opt in it) {
                                            when (h2Opt.key) {
                                                "host" -> bean.host =
                                                    (h2Opt.value as? List<Any>)?.joinToString("\n")

                                                "path" -> bean.path = h2Opt.value?.toString()
                                            }
                                        }
                                    }

                                    "http-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (httpOpt in it) {
                                            when (httpOpt.key) {
                                                "path" -> bean.path =
                                                    (httpOpt.value as? List<Any>)?.joinToString("\n")

                                                "headers" -> {
                                                    (httpOpt.value as? Map<Any, List<Any>>)?.forEach { (key, value) ->
                                                        when (key.toString().lowercase()) {
                                                            "host" -> {
                                                                bean.host = value.joinToString("\n")
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    "grpc-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (grpcOpt in it) {
                                            when (grpcOpt.key) {
                                                "grpc-service-name" -> bean.path =
                                                    grpcOpt.value?.toString()
                                            }
                                        }
                                    }

                                    "smux" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (smuxOpt in it) {
                                            when (smuxOpt.key) {
                                                "enabled" -> bean.enableMux =
                                                    smuxOpt.value.toString() == "true"

                                                "max-streams" -> bean.muxConcurrency =
                                                    smuxOpt.value.toString().toInt()

                                                "padding" -> bean.muxPadding =
                                                    smuxOpt.value.toString() == "true"
                                            }
                                        }
                                    }

                                    "ech-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (echOpt in it) {
                                            when (echOpt.key) {
                                                "enable" -> bean.enableECH =
                                                    echOpt.value.toString() == "true"
                                            }
                                        }
                                    }
                                }
                            }
                            proxies.add(bean)
                        }

                        "anytls" -> {
                            val bean = AnyTLSBean()
                            for (opt in proxy) {
                                if (opt.value == null) continue
                                when (opt.key.replace("_", "-")) {
                                    "name" -> bean.name = opt.value.toString()
                                    "server" -> bean.serverAddress = opt.value as String
                                    "port" -> bean.serverPort = opt.value.toString().toInt()
                                    "password" -> bean.password = opt.value.toString()
                                    "client-fingerprint" -> bean.utlsFingerprint =
                                        opt.value as String

                                    "sni" -> bean.sni = opt.value.toString()
                                    "skip-cert-verify" -> bean.allowInsecure =
                                        opt.value.toString() == "true"

                                    "alpn" -> {
                                        val alpn = (opt.value as? (List<String>))
                                        bean.alpn = alpn?.joinToString("\n")
                                    }
                                }
                            }
                            proxies.add(bean)
                        }

                        "hysteria" -> {
                            val bean = HysteriaBean()
                            bean.protocolVersion = 1
                            var hopPorts = ""
                            for (opt in proxy) {
                                if (opt.value == null) continue
                                when (opt.key.replace("_", "-")) {
                                    "name" -> bean.name = opt.value.toString()
                                    "server" -> bean.serverAddress = opt.value as String
                                    "port" -> bean.serverPorts = opt.value.toString()
                                    "ports" -> hopPorts = opt.value.toString()

                                    "obfs" -> bean.obfuscation = opt.value.toString()

                                    "auth-str" -> {
                                        bean.authPayloadType = HysteriaBean.TYPE_STRING
                                        bean.authPayload = opt.value.toString()
                                    }

                                    "sni" -> bean.sni = opt.value.toString()

                                    "skip-cert-verify" -> bean.allowInsecure =
                                        opt.value.toString() == "true"

                                    "up" -> bean.uploadMbps =
                                        opt.value.toString().substringBefore(" ").toIntOrNull()
                                            ?: 100

                                    "down" -> bean.downloadMbps =
                                        opt.value.toString().substringBefore(" ").toIntOrNull()
                                            ?: 100

                                    "recv-window-conn" -> bean.connectionReceiveWindow =
                                        opt.value.toString().toIntOrNull() ?: 0

                                    "recv-window" -> bean.streamReceiveWindow =
                                        opt.value.toString().toIntOrNull() ?: 0

                                    "disable-mtu-discovery" -> bean.disableMtuDiscovery =
                                        opt.value.toString() == "true" || opt.value.toString() == "1"

                                    "alpn" -> {
                                        val alpn = (opt.value as? (List<String>))
                                        bean.alpn = alpn?.joinToString("\n") ?: "h3"
                                    }
                                }
                            }
                            if (hopPorts.isNotBlank()) {
                                bean.serverPorts = hopPorts
                            }
                            proxies.add(bean)
                        }

                        "hysteria2" -> {
                            val bean = HysteriaBean()
                            bean.protocolVersion = 2
                            var hopPorts = ""
                            for (opt in proxy) {
                                if (opt.value == null) continue
                                when (opt.key.replace("_", "-")) {
                                    "name" -> bean.name = opt.value.toString()
                                    "server" -> bean.serverAddress = opt.value as String
                                    "port" -> bean.serverPorts = opt.value.toString()
                                    "ports" -> hopPorts = opt.value.toString()

                                    "obfs-password" -> bean.obfuscation = opt.value.toString()

                                    "password" -> bean.authPayload = opt.value.toString()

                                    "sni" -> bean.sni = opt.value.toString()

                                    "skip-cert-verify" -> bean.allowInsecure =
                                        opt.value.toString() == "true"

                                    "up" -> bean.uploadMbps =
                                        opt.value.toString().substringBefore(" ").toIntOrNull() ?: 0

                                    "down" -> bean.downloadMbps =
                                        opt.value.toString().substringBefore(" ").toIntOrNull() ?: 0
                                }
                            }
                            if (hopPorts.isNotBlank()) {
                                bean.serverPorts = hopPorts
                            }
                            proxies.add(bean)
                        }

                        "tuic" -> {
                            val bean = TuicBean()
                            var ip = ""
                            for (opt in proxy) {
                                if (opt.value == null) continue
                                when (opt.key.replace("_", "-")) {
                                    "name" -> bean.name = opt.value.toString()
                                    "server" -> bean.serverAddress = opt.value.toString()
                                    "ip" -> ip = opt.value.toString()
                                    "port" -> bean.serverPort = opt.value.toString().toInt()

                                    "token" -> {
                                        bean.protocolVersion = 4
                                        bean.token = opt.value.toString()
                                    }

                                    "uuid" -> bean.uuid = opt.value.toString()

                                    "password" -> bean.token = opt.value.toString()

                                    "skip-cert-verify" -> bean.allowInsecure =
                                        opt.value.toString() == "true"

                                    "disable-sni" -> bean.disableSNI =
                                        opt.value.toString() == "true"

                                    "reduce-rtt" -> bean.reduceRTT =
                                        opt.value.toString() == "true"

                                    "sni" -> bean.sni = opt.value.toString()

                                    "alpn" -> {
                                        val alpn = (opt.value as? (List<String>))
                                        bean.alpn = alpn?.joinToString("\n")
                                    }

                                    "congestion-controller" -> bean.congestionController =
                                        opt.value.toString()

                                    "udp-relay-mode" -> bean.udpRelayMode = opt.value.toString()

                                }
                            }
                            if (ip.isNotBlank()) {
                                bean.serverAddress = ip
                                if (bean.sni.isNullOrBlank() && !bean.serverAddress.isNullOrBlank() && !bean.serverAddress.isIpAddress()) {
                                    bean.sni = bean.serverAddress
                                }
                            }
                            proxies.add(bean)
                        }
                    }
                }

                // Fix ent
                proxies.forEach {
                    it.initializeDefaultValues()
                    if (it is StandardV2RayBean) {
                        // 1. SNI
                        if (it.isTLS() && it.sni.isNullOrBlank() && !it.host.isNullOrBlank() && !it.host.isIpAddress()) {
                            it.sni = it.host
                        }
                        // 2. globalClientFingerprint
                        if (!it.realityPubKey.isNullOrBlank() && it.utlsFingerprint.isNullOrBlank()) {
                            it.utlsFingerprint = globalClientFingerprint
                            if (it.utlsFingerprint.isNullOrBlank()) it.utlsFingerprint = "chrome"
                        }
                    }
                }
                budget.requireProfileCount(proxies.size)
                return proxies
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubscriptionParseLimitException) {
                throw e
            } catch (e: Exception) {
                if (e.isSubscriptionYamlLimit()) {
                    throw SubscriptionParseLimitException(
                        "Subscription YAML exceeds parser limits",
                        e,
                    )
                }
                Logs.w(e)
            }
        }

        if (HYSTERIA_SERVER_LINE.containsMatchIn(text)) {
            try {
                val budget = SubscriptionParseBudget()
                val config = loadSubscriptionYamlMap(text)
                config.parseHysteriaConfig()?.let { bean ->
                    budget.requireProfileCount(1)
                    return listOf(bean)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubscriptionParseLimitException) {
                throw e
            } catch (e: Exception) {
                if (e.isSubscriptionYamlLimit()) {
                    throw SubscriptionParseLimitException(
                        "Subscription YAML exceeds parser limits",
                        e,
                    )
                }
                Logs.w(e)
            }
        }

        if (text.contains("[Interface]")) {
            // wireguard
            try {
                proxies.addAll(parseWireGuard(text).map {
                    if (fileName.isNotBlank()) it.name = fileName.removeSuffix(".conf")
                    it
                })
                return proxies
            } catch (e: SubscriptionParseLimitException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
            }
        }

        try {
            SubscriptionParseBudget().validateJsonNesting(text)
            val json = parseJsonValueWithBudget(text)
            SubscriptionParseBudget().validateDecodedTree(json)
            val result = parseJSON(json)
            SubscriptionParseBudget().requireProfileCount(result.size)
            return result
        } catch (e: SubscriptionParseLimitException) {
            throw e
        } catch (e: Exception) {
            Logs.w(e)
        }

        try {
            val decoded = text.decodeBase64UrlSafe()
            val budget = SubscriptionParseBudget()
            budget.validateLinkText(decoded)
            val result = parseProxies(decoded).takeIf { it.isNotEmpty() } ?: error("Not found")
            budget.requireProfileCount(result.size)
            return result
        } catch (e: SubscriptionParseLimitException) {
            throw e
        } catch (e: Exception) {
            Logs.w(e)
        }

        try {
            val budget = SubscriptionParseBudget()
            budget.validateLinkText(text)
            val result = parseProxies(text).takeIf { it.isNotEmpty() } ?: error("Not found")
            budget.requireProfileCount(result.size)
            return result
        } catch (e: SubscriptionFoundException) {
            throw e
        } catch (e: SubscriptionParseLimitException) {
            throw e
        } catch (e: Exception) {
            Logs.w(e)
        }

        return null
    }

    fun clashCipher(cipher: String): String {
        return when (cipher) {
            "dummy" -> "none"
            else -> cipher
        }
    }

    fun parseWireGuard(conf: String): List<WireGuardBean> {
        SubscriptionParseBudget().validateIniText(conf)
        val ini = Ini().apply {
            config.setMultiOption(true)
            config.setMultiSection(true)
            config.setEscapeNewline(true)
            load(StringReader(conf))
        }
        val iface = ini["Interface"] ?: error("Missing 'Interface' selection")
        val peers = ini.getAll("Peer")
        if (peers.isNullOrEmpty()) error("Missing 'Peer' selections")
        val budget = SubscriptionParseBudget()
        budget.requireProfileCount(peers.size)
        budget.validateDecodedTree(iface)
        budget.validateDecodedTree(peers)

        val bean = WireGuardBean().applyDefaultValues()
        val localAddresses = iface.getAll("Address")
        if (localAddresses.isNullOrEmpty()) error("Empty address in 'Interface' selection")
        bean.localAddress = localAddresses.flatMap { it.split(",") }.joinToString("\n")
        budget.recordField(bean.localAddress, includeInDecodedBytes = false)
        bean.privateKey = iface["PrivateKey"]
        iface["MTU"]?.toIntOrNull()?.let { bean.mtu = it }
        val beans = mutableListOf<WireGuardBean>()
        for (peer in peers) {
            val endpoint = peer["Endpoint"]
            if (endpoint.isNullOrBlank() || !endpoint.contains(":")) {
                continue
            }

            val peerBean = bean.clone()
            peerBean.serverAddress = endpoint.substringBeforeLast(":")
            peerBean.serverPort = endpoint.substringAfterLast(":").toIntOrNull() ?: continue
            peerBean.peerPublicKey = peer["PublicKey"] ?: continue
            peerBean.peerPreSharedKey = peer["PresharedKey"]
            beans.add(peerBean.applyDefaultValues())
        }
        if (beans.isEmpty()) error("Empty available peer list")
        return beans
    }

    fun parseJSON(json: Any): List<AbstractBean> = parseJSON(json, SubscriptionParseBudget())

    internal fun parseJSON(
        json: Any,
        budget: SubscriptionParseBudget,
    ): List<AbstractBean> = parseJSON(json, 1, budget)

    private fun parseJSON(
        json: Any,
        nestingDepth: Int,
        budget: SubscriptionParseBudget,
    ): List<AbstractBean> {
        if (nestingDepth > MAX_SUBSCRIPTION_NESTING_DEPTH) {
            throw SubscriptionParseLimitException(
                "Subscription JSON exceeds nesting depth $MAX_SUBSCRIPTION_NESTING_DEPTH",
            )
        }
        val proxies = ArrayList<AbstractBean>()

        if (json is JSONObject) {
            when {
                json.has("method") -> {
                    budget.recordProfile()
                    return listOf(json.parseShadowsocks())
                }

                json.has("remote_addr") -> {
                    budget.recordProfile()
                    return listOf(json.parseTrojanGo())
                }

                json.has("outbounds") -> {
                    val outbounds = json.getJSONArray("outbounds")
                    for (index in 0 until outbounds.length()) {
                        val outbound = outbounds.opt(index) as? JSONObject ?: continue
                        val type = outbound.getStr("type")
                        if (type.isNullOrEmpty() ||
                            type == "dns" || type == "block" || type == "direct" ||
                            type == "selector" || type == "urltest"
                        ) {
                            continue
                        }
                        budget.recordProfile()
                        proxies.add(ConfigBean().apply {
                            applyDefaultValues()
                            this.type = 1
                            config = outbound.toStringPretty()
                            name = outbound.getStr("tag")
                        })
                    }
                    return proxies
                }

            }

            json.parseHysteriaJson()?.let {
                // Hysteria's parser decides the version from several schema variants. Record the
                // result before retaining it so an over-budget bean never enters the result list.
                budget.recordProfile()
                return listOf(it)
            }

            if (json.has("server") && json.has("server_port")) {
                budget.recordProfile()
                return listOf(ConfigBean().applyDefaultValues().apply {
                    type = 1
                    config = json.toStringPretty()
                })
            }
        } else {
            json as JSONArray
            json.forEach { _, it ->
                if (isJsonObjectValid(it)) {
                    proxies.addAll(parseJSON(it, nestingDepth + 1, budget))
                }
            }
        }

        proxies.forEach { it.initializeDefaultValues() }
        return proxies
    }

    private class ProfileIdentity(bean: AbstractBean) {

        private val normalizedBean = bean.clone().apply {
            name = ""
            customOutboundJson = ""
            customConfigJson = ""
        }
        private val cachedHashCode = 31 * normalizedBean.javaClass.hashCode() +
                normalizedBean.hashCode()

        override fun equals(other: Any?): Boolean {
            return other is ProfileIdentity &&
                    cachedHashCode == other.cachedHashCode &&
                    normalizedBean == other.normalizedBean
        }

        override fun hashCode(): Int = cachedHashCode
    }

}
