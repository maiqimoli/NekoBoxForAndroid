package io.nekohasekai.sagernet.fmt.socks

import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.decodeBase64UrlSafe
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.unUrlSafe
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.NGUtil
import moe.matsuri.nb4a.utils.Util
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private fun plainSocksDisplayName(username: String): String {
    val id = listOf(
        Regex("""(?i)(?:^|[-_:.])id[-_:.]?([a-z0-9]{4,})"""),
        Regex("""(?i)(?:^|[-_:.])session[-_:.]?([a-z0-9]{4,})"""),
        Regex("""(?:^|[-_:.])([0-9]{6,})(?:$|[-_:.])"""),
    ).firstNotNullOfOrNull { regex ->
        regex.find(username)?.groupValues?.getOrNull(1)
    }
    return id?.let { "SOCKS ID $it" } ?: ""
}

private data class PlainSOCKSParts(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
)

private fun parsePlainSOCKSParts(link: String): PlainSOCKSParts? {
    val trimmed = link.trim()
    val parts = if (trimmed.startsWith("[")) {
        val hostEnd = trimmed.indexOf("]:")
        if (hostEnd < 0) return null
        val host = trimmed.substring(1, hostEnd)
        val rest = trimmed.substring(hostEnd + 2).split(":", limit = 3)
        if (rest.size != 3) return null
        listOf(host, rest[0], rest[1], rest[2])
    } else {
        trimmed.split(":", limit = 4)
    }

    if (parts.size != 4) return null
    val port = parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    if (parts[0].isBlank() || parts[2].isBlank() || parts[3].isBlank()) return null
    return PlainSOCKSParts(parts[0], port, parts[2], parts[3])
}

fun parsePlainSOCKS(link: String): SOCKSBean? {
    val parts = parsePlainSOCKSParts(link) ?: return null

    return SOCKSBean().apply {
        protocol = SOCKSBean.PROTOCOL_SOCKS5
        serverAddress = parts.host
        serverPort = parts.port
        username = parts.username
        password = parts.password
        name = plainSocksDisplayName(username)
    }
}

fun parseSOCKS(link: String): SOCKSBean {
    val url = ("http://" + link.substringAfter("://")).toHttpUrlOrNull()
        ?: error("Not supported: $link")

    return SOCKSBean().apply {
        protocol = when {
            link.startsWith("socks4://") -> SOCKSBean.PROTOCOL_SOCKS4
            link.startsWith("socks4a://") -> SOCKSBean.PROTOCOL_SOCKS4A
            else -> SOCKSBean.PROTOCOL_SOCKS5
        }
        name = url.fragment
        serverAddress = url.host
        serverPort = url.port
        username = url.username
        password = url.password
        // v2rayN fmt
        if (password.isNullOrBlank() && !username.isNullOrBlank()) {
            try {
                val n = username.decodeBase64UrlSafe()
                username = n.substringBefore(":")
                password = n.substringAfter(":")
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    }
}

fun SOCKSBean.toUri(): String {

    val builder = HttpUrl.Builder().scheme("http").host(serverAddress).port(serverPort)
    if (!username.isNullOrBlank()) builder.username(username)
    if (!password.isNullOrBlank()) builder.password(password)
    if (!name.isNullOrBlank()) builder.encodedFragment(name.urlSafe())
    return builder.toLink("socks${protocolVersion()}")

}

fun SOCKSBean.toV2rayN(): String {

    var link = ""
    if (username.isNotBlank()) {
        link += username.urlSafe() + ":" + password.urlSafe() + "@"
    }
    link += "$serverAddress:$serverPort"
    link = "socks://" + NGUtil.encode(link)
    if (name.isNotBlank()) {
        link += "#" + name.urlSafe()
    }

    return link

}

fun buildSingBoxOutboundSocksBean(bean: SOCKSBean): SingBoxOptions.Outbound_SocksOptions {
    return SingBoxOptions.Outbound_SocksOptions().apply {
        type = "socks"
        server = bean.serverAddress
        server_port = bean.serverPort
        username = bean.username
        password = bean.password
        version = bean.protocolVersionName()
    }
}
