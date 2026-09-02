package io.nekohasekai.sagernet.fmt.hysteria

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt


// hysteria://host:port?auth=123456&peer=sni.domain&insecure=1|0&upmbps=100&downmbps=100&alpn=hysteria&obfs=xplus&obfsParam=123456#remarks
fun parseHysteria1(url: String): HysteriaBean {
    val link = url.replace("hysteria://", "https://").toHttpUrlOrNull() ?: error(
        "invalid hysteria link $url"
    )
    return HysteriaBean().apply {
        protocolVersion = 1
        serverAddress = link.host
        serverPorts = link.port.toString()
        name = link.fragment

        link.queryParameter("mport")?.also {
            serverPorts = it
        }
        link.queryParameter("peer")?.also {
            sni = it
        }
        link.queryParameter("auth")?.takeIf { it.isNotBlank() }?.also {
            authPayloadType = HysteriaBean.TYPE_STRING
            authPayload = it
        }
        link.queryParameter("insecure")?.also {
            allowInsecure = it == "1" || it == "true"
        }
        link.queryParameter("upmbps")?.also {
            uploadMbps = it.toIntOrNull() ?: uploadMbps
        }
        link.queryParameter("downmbps")?.also {
            downloadMbps = it.toIntOrNull() ?: downloadMbps
        }
        link.queryParameter("alpn")?.also {
            alpn = it
        }
        link.queryParameter("obfsParam")?.also {
            obfuscation = it
        }
        link.queryParameter("protocol")?.also {
            when (it) {
                "faketcp" -> {
                    protocol = HysteriaBean.PROTOCOL_FAKETCP
                }

                "wechat-video" -> {
                    protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO
                }
            }
        }
    }
}

// hysteria2://[auth@]hostname[:port]/?[key=value]&[key=value]...
fun parseHysteria2(url: String): HysteriaBean {
    val link = url
        .replace("hysteria2://", "https://")
        .replace("hy2://", "https://")
        .toHttpUrlOrNull() ?: error("invalid hysteria link $url")
    return HysteriaBean().apply {
        protocolVersion = 2
        serverAddress = link.host
        serverPorts = link.port.toString()
        authPayload = if (link.password.isNotBlank()) {
            link.username + ":" + link.password
        } else {
            link.username
        }
        name = link.fragment

        link.queryParameter("mport")?.also {
            serverPorts = it
        }
        link.queryParameter("sni")?.also {
            sni = it
        }
        link.queryParameter("insecure")?.also {
            allowInsecure = it == "1" || it == "true"
        }
//        link.queryParameter("upmbps")?.also {
//            uploadMbps = it.toIntOrNull() ?: uploadMbps
//        }
//        link.queryParameter("downmbps")?.also {
//            downloadMbps = it.toIntOrNull() ?: downloadMbps
//        }
        link.queryParameter("obfs-password")?.also {
            obfuscation = it
        }
//        link.queryParameter("pinSHA256")?.also {
//            // TODO your box do not support it
//        }
    }
}

fun HysteriaBean.toUri(): String {
    var un = ""
    var pw = ""
    if (protocolVersion == 2) {
        if (authPayload.contains(":")) {
            un = authPayload.substringBefore(":")
            pw = authPayload.substringAfter(":")
        } else {
            un = authPayload
        }
    }
    //
    val builder = linkBuilder()
        .host(serverAddress)
        .port(getFirstPort(serverPorts))
        .username(un)
        .password(pw)
    if (isMultiPort(displayAddress())) {
        builder.addQueryParameter("mport", serverPorts)
    }
    if (name.isNotBlank()) {
        builder.encodedFragment(name.urlSafe())
    }
    if (allowInsecure) {
        builder.addQueryParameter("insecure", "1")
    }
    if (protocolVersion == 1) {
        if (sni.isNotBlank()) {
            builder.addQueryParameter("peer", sni)
        }
        if (authPayload.isNotBlank()) {
            builder.addQueryParameter("auth", authPayload)
        }
        builder.addQueryParameter("upmbps", "$uploadMbps")
        builder.addQueryParameter("downmbps", "$downloadMbps")
        if (alpn.isNotBlank()) {
            builder.addQueryParameter("alpn", alpn)
        }
        if (obfuscation.isNotBlank()) {
            builder.addQueryParameter("obfs", "xplus")
            builder.addQueryParameter("obfsParam", obfuscation)
        }
        when (protocol) {
            HysteriaBean.PROTOCOL_FAKETCP -> {
                builder.addQueryParameter("protocol", "faketcp")
            }

            HysteriaBean.PROTOCOL_WECHAT_VIDEO -> {
                builder.addQueryParameter("protocol", "wechat-video")
            }
        }
    } else {
        if (sni.isNotBlank()) {
            builder.addQueryParameter("sni", sni)
        }
        if (obfuscation.isNotBlank()) {
            builder.addQueryParameter("obfs", "salamander")
            builder.addQueryParameter("obfs-password", obfuscation)
        }
    }
    return builder.toLink(if (protocolVersion == 2) "hy2" else "hysteria")
}

/** Parses a standalone Hysteria client config represented by JSON/YAML scalar collections. */
internal fun Map<*, *>.parseHysteriaConfig(): HysteriaBean? {
    val server = configString("server") ?: return null
    val hasExplicitVersion = hasConfigKey("version", "protocol-version", "protocol_version")
    val explicitVersion = configValue("version", "protocol-version", "protocol_version")
        ?.toString()
        ?.trim()
        ?.toIntOrNull()
    val type = configString("type")?.lowercase(Locale.ROOT)
    val typeVersion = when (type) {
        "hysteria" -> 1
        "hysteria2", "hy2" -> 2
        else -> null
    }

    if (type != null && type !in HYSTERIA_CONFIG_TYPES) return null
    if (hasExplicitVersion && explicitVersion != 1 && explicitVersion != 2) return null
    if (explicitVersion != null && typeVersion != null && explicitVersion != typeVersion) return null

    val hasHysteria2Schema = configValue("bandwidth") is Map<*, *> ||
            configValue("tls") is Map<*, *> ||
            configValue("transport") is Map<*, *> ||
            configValue("quic") is Map<*, *> ||
            configValue("obfs") is Map<*, *>
    val hasHysteria1Schema = hasConfigKey(
        "up-mbps", "up_mbps", "down-mbps", "down_mbps", "up", "down",
        "auth-str", "auth_str", "server-name", "server_name", "recv-window",
        "recv_window", "protocol", "alpn", "disable-mtu-discovery",
    ) || (configValue("obfs") != null && configValue("obfs") !is Map<*, *>)

    val isHysteria2 = when {
        explicitVersion == 2 -> true
        explicitVersion == 1 -> false
        typeVersion == 2 -> true
        typeVersion == 1 -> false
        hasHysteria1Schema -> false
        // Hysteria 2's minimal client config is just server + auth. HY1 configs normally carry
        // one of the legacy bandwidth/auth/protocol keys handled above.
        hasConfigKey("auth") -> true
        hasHysteria2Schema && hasConfigKey("password") -> true
        else -> return null
    }

    return if (isHysteria2) {
        parseHysteria2Config(server)
    } else {
        parseHysteria1Config(server)
    }
}

/** Keeps the historical API while sharing endpoint and scalar handling with YAML imports. */
fun JSONObject.parseHysteria1Json(): HysteriaBean {
    val config = toConfigMap()
    val server = config.configString("server") ?: error("Missing Hysteria server")
    return config.parseHysteria1Config(server)
}

fun JSONObject.parseHysteriaJson(): HysteriaBean? {
    if (!has("server")) return null
    return toConfigMap().parseHysteriaConfig()
}

private fun Map<*, *>.parseHysteria1Config(server: String): HysteriaBean {
    val endpoint = parseHysteriaEndpoint(server, configValue("server-port", "server_port"))
    return HysteriaBean().apply {
        protocolVersion = 1
        serverAddress = endpoint.host
        serverPorts = endpoint.ports

        configInt("up-mbps", "up_mbps", "up")?.let { uploadMbps = it }
        configInt("down-mbps", "down_mbps", "down")?.let { downloadMbps = it }
        configString("obfs")?.let { obfuscation = it }
        configString("auth")?.let {
            authPayloadType = HysteriaBean.TYPE_BASE64
            authPayload = it
        }
        configString("auth-str", "auth_str")?.let {
            authPayloadType = HysteriaBean.TYPE_STRING
            authPayload = it
        }
        when (configString("protocol")?.lowercase(Locale.ROOT)) {
            "faketcp" -> protocol = HysteriaBean.PROTOCOL_FAKETCP
            "wechat-video" -> protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO
        }
        configString("server-name", "server_name", "sni")?.let { sni = it }
        configStringList("alpn")?.let { alpn = it.joinToString("\n") }
        configBoolean("insecure", "skip-cert-verify")?.let { allowInsecure = it }
        configInt("recv-window-conn", "recv_window_conn")?.let { streamReceiveWindow = it }
        configInt("recv-window", "recv_window")?.let { connectionReceiveWindow = it }
        configBoolean("disable-mtu-discovery", "disable_mtu_discovery")
            ?.let { disableMtuDiscovery = it }
        configDurationSeconds("hop-interval", "hop_interval")?.let { hopInterval = it }
        configString("name")?.let { name = it }
        initializeDefaultValues()
    }
}

private fun Map<*, *>.parseHysteria2Config(server: String): HysteriaBean {
    val endpoint = parseHysteriaEndpoint(
        server,
        configValue("server-port", "server_port", "server-ports", "server_ports"),
    )
    val bandwidth = configMap("bandwidth")
    val tls = configMap("tls")
    val obfs = configMap("obfs")
    val transport = configMap("transport")
    val udpTransport = transport?.configMap("udp")

    return HysteriaBean().apply {
        protocolVersion = 2
        serverAddress = endpoint.host
        serverPorts = endpoint.ports
        authPayloadType = HysteriaBean.TYPE_STRING
        configString("auth", "password")?.let { authPayload = it }

        (bandwidth?.configBandwidthMbps("up") ?: configBandwidthMbps("up"))
            ?.let { uploadMbps = it }
        (bandwidth?.configBandwidthMbps("down") ?: configBandwidthMbps("down"))
            ?.let { downloadMbps = it }

        (tls?.configString("sni", "server-name", "server_name")
            ?: configString("sni", "server-name", "server_name"))?.let { sni = it }
        (tls?.configBoolean("insecure", "skip-cert-verify")
            ?: configBoolean("insecure", "skip-cert-verify"))?.let { allowInsecure = it }

        val salamander = obfs?.configMap("salamander")
        val obfsType = obfs?.configString("type")?.lowercase(Locale.ROOT)
        val obfsPassword = when {
            obfs == null -> configString("obfs-password", "obfs_password")
            obfsType == null || obfsType == "salamander" -> {
                salamander?.configString("password") ?: obfs.configString("password")
            }

            else -> null
        }
        obfsPassword?.let { obfuscation = it }

        (udpTransport?.configDurationSeconds("hop-interval", "hop_interval", "hopInterval")
            ?: transport?.configDurationSeconds("hop-interval", "hop_interval", "hopInterval")
            ?: configDurationSeconds("hop-interval", "hop_interval", "hopInterval"))
            ?.let { hopInterval = it }
        configString("name")?.let { name = it }
        initializeDefaultValues()
    }
}

private data class HysteriaEndpoint(val host: String, val ports: String)

private fun parseHysteriaEndpoint(server: String, explicitPort: Any?): HysteriaEndpoint {
    val trimmed = server.trim()
    require(trimmed.isNotEmpty()) { "Missing Hysteria server" }

    val explicitPorts = when (explicitPort) {
        is Iterable<*> -> explicitPort.mapNotNull { it?.toString() }.joinToString(",")
        is Array<*> -> explicitPort.mapNotNull { it?.toString() }.joinToString(",")
        null -> ""
        else -> explicitPort.toString()
    }.trim()
    val endpoint = if (trimmed.startsWith("[")) {
        val closingBracket = trimmed.indexOf(']')
        require(closingBracket > 1) { "Invalid Hysteria server: $server" }
        val host = trimmed.substring(1, closingBracket)
        val suffix = trimmed.substring(closingBracket + 1)
        require(suffix.isEmpty() || suffix.startsWith(":")) {
            "Invalid Hysteria server: $server"
        }
        HysteriaEndpoint(host, suffix.removePrefix(":").ifBlank { "443" })
    } else if (trimmed.count { it == ':' } == 1) {
        val separator = trimmed.lastIndexOf(':')
        HysteriaEndpoint(
            trimmed.substring(0, separator),
            trimmed.substring(separator + 1).ifBlank { "443" },
        )
    } else {
        HysteriaEndpoint(trimmed, "443")
    }
    return if (explicitPorts.isEmpty()) endpoint else endpoint.copy(ports = explicitPorts)
}

private fun Map<*, *>.configValue(vararg names: String): Any? {
    val normalizedNames = names.mapTo(HashSet(names.size), ::normalizeConfigKey)
    return entries.firstOrNull { (key, _) ->
        key != null && normalizeConfigKey(key.toString()) in normalizedNames
    }?.value
}

private fun Map<*, *>.hasConfigKey(vararg names: String): Boolean {
    val normalizedNames = names.mapTo(HashSet(names.size), ::normalizeConfigKey)
    return keys.any { key -> key != null && normalizeConfigKey(key.toString()) in normalizedNames }
}

private fun normalizeConfigKey(key: String): String =
    key.trim().lowercase(Locale.ROOT).replace('_', '-')

private fun Map<*, *>.configString(vararg names: String): String? =
    configValue(*names)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

private fun Map<*, *>.configMap(vararg names: String): Map<*, *>? =
    configValue(*names) as? Map<*, *>

private fun Map<*, *>.configInt(vararg names: String): Int? {
    return when (val value = configValue(*names)) {
        is Number -> value.toInt()
        else -> value?.toString()?.trim()?.substringBefore(' ')?.toIntOrNull()
    }
}

private fun Map<*, *>.configBoolean(vararg names: String): Boolean? {
    return when (val value = configValue(*names)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        null -> null
        else -> when (value.toString().trim().lowercase(Locale.ROOT)) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }
    }
}

private fun Map<*, *>.configStringList(vararg names: String): List<String>? {
    return when (val value = configValue(*names)) {
        is Iterable<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        is Array<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        null -> null
        else -> value.toString().split(',').map(String::trim).filter(String::isNotEmpty)
    }.takeUnless { it.isNullOrEmpty() }
}

private fun Map<*, *>.configBandwidthMbps(vararg names: String): Int? {
    val value = configValue(*names) ?: return null
    if (value is Number) return value.toDouble().roundToInt().coerceAtLeast(0)

    val match = BANDWIDTH_PATTERN.matchEntire(value.toString()) ?: return null
    val amount = match.groupValues[1].toDoubleOrNull() ?: return null
    val multiplier = when (match.groupValues[2].lowercase(Locale.ROOT)) {
        "k" -> 0.001
        "g" -> 1_000.0
        "t" -> 1_000_000.0
        else -> 1.0
    }
    return (amount * multiplier).roundToInt().coerceAtLeast(0)
}

private fun Map<*, *>.configDurationSeconds(vararg names: String): Int? {
    val value = configValue(*names) ?: return null
    if (value is Number) return value.toInt().coerceAtLeast(0)

    val match = DURATION_PATTERN.matchEntire(value.toString()) ?: return null
    val amount = match.groupValues[1].toDoubleOrNull() ?: return null
    val multiplier = when (match.groupValues[2].lowercase(Locale.ROOT)) {
        "ms" -> 0.001
        "m" -> 60.0
        "h" -> 3_600.0
        else -> 1.0
    }
    return (amount * multiplier).roundToInt().coerceAtLeast(0)
}

private val BANDWIDTH_PATTERN =
    Regex("""\s*(\d+(?:\.\d+)?)\s*([kmgt]?)(?:b(?:it)?(?:/s|ps)?)?\s*""", RegexOption.IGNORE_CASE)
private val DURATION_PATTERN =
    Regex("""\s*(\d+(?:\.\d+)?)\s*(ms|s|m|h)?\s*""", RegexOption.IGNORE_CASE)
private val HYSTERIA_CONFIG_TYPES = setOf("hysteria", "hysteria2", "hy2")

private fun JSONObject.toConfigMap(depth: Int = 1): Map<String, Any?> {
    require(depth <= MAX_SUBSCRIPTION_NESTING_DEPTH) {
        "Hysteria JSON exceeds nesting depth $MAX_SUBSCRIPTION_NESTING_DEPTH"
    }
    val result = LinkedHashMap<String, Any?>()
    keys().forEach { key -> result[key] = opt(key).toConfigValue(depth + 1) }
    return result
}

private fun Any?.toConfigValue(depth: Int): Any? = when (this) {
    null, JSONObject.NULL -> null
    is JSONObject -> toConfigMap(depth)
    is JSONArray -> {
        require(depth <= MAX_SUBSCRIPTION_NESTING_DEPTH) {
            "Hysteria JSON exceeds nesting depth $MAX_SUBSCRIPTION_NESTING_DEPTH"
        }
        List(length()) { index -> opt(index).toConfigValue(depth + 1) }
    }
    else -> this
}

fun HysteriaBean.buildHysteria1Config(port: Int, cacheFile: (() -> File)?): String {
    if (protocolVersion != 1) {
        throw Exception("error version: $protocolVersion")
    }
    return JSONObject().apply {
        put("server", displayAddress())
        when (protocol) {
            HysteriaBean.PROTOCOL_FAKETCP -> {
                put("protocol", "faketcp")
            }

            HysteriaBean.PROTOCOL_WECHAT_VIDEO -> {
                put("protocol", "wechat-video")
            }
        }
        put("up_mbps", uploadMbps)
        put("down_mbps", downloadMbps)
        put(
            "socks5", JSONObject(
                mapOf(
                    "listen" to "$LOCALHOST:$port",
                )
            )
        )
        put("retry", 5)
        put("fast_open", true)
        put("lazy_start", true)
        put("obfs", obfuscation)
        when (authPayloadType) {
            HysteriaBean.TYPE_BASE64 -> put("auth", authPayload)
            HysteriaBean.TYPE_STRING -> put("auth_str", authPayload)
        }
        if (sni.isBlank() && finalAddress == LOCALHOST && !serverAddress.isIpAddress()) {
            sni = serverAddress
        }
        if (sni.isNotBlank()) {
            put("server_name", sni)
        }
        if (alpn.isNotBlank()) put("alpn", alpn)
        if (caText.isNotBlank() && cacheFile != null) {
            val caFile = cacheFile()
            caFile.writeText(caText)
            put("ca", caFile.absolutePath)
        }

        if (allowInsecure) put("insecure", true)
        if (streamReceiveWindow > 0) put("recv_window_conn", streamReceiveWindow)
        if (connectionReceiveWindow > 0) put("recv_window", connectionReceiveWindow)
        if (disableMtuDiscovery) put("disable_mtu_discovery", true)

        put("hop_interval", hopInterval)
    }.toStringPretty()
}

fun isMultiPort(hyAddr: String): Boolean {
    if (!hyAddr.contains(":")) return false
    val p = hyAddr.substringAfterLast(":")
    if (p.contains("-") || p.contains(",")) return true
    return false
}

fun getFirstPort(portStr: String): Int {
    return portStr.substringBefore(":").substringBefore(",").toIntOrNull() ?: 443
}

fun HysteriaBean.canUseSingBox(): Boolean {
    if (protocol != HysteriaBean.PROTOCOL_UDP) return false
    return true
}

fun buildSingBoxOutboundHysteriaBean(bean: HysteriaBean): SingBoxOptions.SingBoxOption {
    return when (bean.protocolVersion) {
        1 -> SingBoxOptions.Outbound_HysteriaOptions().apply {
            type = "hysteria"
            server = bean.serverAddress
            val port = bean.serverPorts.toIntOrNull()
            if (port != null) {
                server_port = port
            } else {
                server_ports = hopPortsToSingboxList(bean.serverPorts)
            }
            hop_interval = "${bean.hopInterval}s"
            up_mbps = bean.uploadMbps
            down_mbps = bean.downloadMbps
            obfs = bean.obfuscation
            disable_mtu_discovery = bean.disableMtuDiscovery
            when (bean.authPayloadType) {
                HysteriaBean.TYPE_BASE64 -> auth = bean.authPayload
                HysteriaBean.TYPE_STRING -> auth_str = bean.authPayload
            }
            if (bean.streamReceiveWindow > 0) {
                recv_window_conn = bean.streamReceiveWindow.toLong()
            }
            if (bean.connectionReceiveWindow > 0) {
                recv_window = bean.connectionReceiveWindow.toLong()
            }
            tls = SingBoxOptions.OutboundTLSOptions().apply {
                if (bean.sni.isNotBlank()) {
                    server_name = bean.sni
                }
                if (bean.alpn.isNotBlank()) {
                    alpn = bean.alpn.listByLineOrComma()
                }
                if (bean.caText.isNotBlank()) {
                    certificate = bean.caText
                }
                insecure = bean.allowInsecure || DataStore.globalAllowInsecure
                enabled = true
            }
        }

        2 -> SingBoxOptions.Outbound_Hysteria2Options().apply {
            type = "hysteria2"
            server = bean.serverAddress
            val port = bean.serverPorts.toIntOrNull()
            if (port != null) {
                server_port = port
            } else {
                server_ports = hopPortsToSingboxList(bean.serverPorts)
            }
            hop_interval = "${bean.hopInterval}s"
            up_mbps = bean.uploadMbps
            down_mbps = bean.downloadMbps
            if (bean.obfuscation.isNotBlank()) {
                obfs = SingBoxOptions.Hysteria2Obfs().apply {
                    type = "salamander"
                    password = bean.obfuscation
                }
            }
//            disable_mtu_discovery = bean.disableMtuDiscovery
            password = bean.authPayload
//            if (bean.streamReceiveWindow > 0) {
//                recv_window_conn = bean.streamReceiveWindow.toLong()
//            }
//            if (bean.connectionReceiveWindow > 0) {
//                recv_window_conn = bean.connectionReceiveWindow.toLong()
//            }
            tls = SingBoxOptions.OutboundTLSOptions().apply {
                if (bean.sni.isNotBlank()) {
                    server_name = bean.sni
                }
                alpn = listOf("h3")
                if (bean.caText.isNotBlank()) {
                    certificate = bean.caText
                }
                insecure = bean.allowInsecure || DataStore.globalAllowInsecure
                enabled = true
            }
        }

        else -> error("error_version $bean.protocolVersion")
    }
}

fun hopPortsToSingboxList(s: String): List<String> {
    return s.split(",").mapNotNull {
        val pRange = it.replace("-", ":")
        if (pRange.split(":").size == 2) {
            pRange
        } else {
            null
        }
    }
}
