package io.nekohasekai.sagernet.group

import java.net.URI
import java.util.Locale

internal fun requireSecureSubscriptionUrl(value: String) {
    val uri = runCatching { URI(value) }
        .getOrElse { throw IllegalArgumentException("Invalid subscription URL", it) }
    require(!uri.isOpaque && uri.host?.isNotBlank() == true) { "Invalid subscription URL" }

    when (uri.scheme?.lowercase(Locale.ROOT)) {
        "https" -> Unit
        "http" -> require(isLoopbackSubscriptionHost(uri.host)) {
            "HTTP subscriptions are restricted to loopback hosts"
        }
        else -> throw IllegalArgumentException("Subscriptions must use HTTPS")
    }
}

private fun isLoopbackSubscriptionHost(host: String): Boolean {
    val normalized = host.trim('[', ']').lowercase(Locale.ROOT)
    if (normalized == "localhost" || normalized == "::1") return true
    val octets = normalized.split('.')
    return octets.size == 4 && octets[0] == "127" && octets.all { octet ->
        octet.isNotEmpty() && octet.length <= 3 && octet.all(Char::isDigit) &&
            octet.toIntOrNull() in 0..255
    }
}
