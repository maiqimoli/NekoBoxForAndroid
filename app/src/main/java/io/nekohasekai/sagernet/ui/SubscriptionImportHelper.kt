package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal object SubscriptionImportHelper {

    private val directUrlKeys = listOf("url", "link", "subscription", "subscription_url", "target")
    private val displayNameKeys = listOf("name", "title", "label")

    fun shouldImportSubscription(scheme: String?, host: String?): Boolean {
        val normalizedScheme = scheme?.lowercase(Locale.US) ?: return false
        val normalizedHost = host?.lowercase(Locale.US)
        return when (normalizedScheme) {
            "sn" -> normalizedHost == "subscription"
            "clash" -> normalizedHost == null || normalizedHost == "install-config"
            else -> false
        }
    }

    fun createDirectSubscriptionGroup(
        scheme: String?,
        host: String?,
        encodedPath: String?,
        encodedQuery: String?,
        encodedFragment: String?,
        queryParameter: (String) -> String?,
    ): ProxyGroup? {
        if (!shouldImportSubscription(scheme, host)) return null

        val looseQueryParameters = parseLooseParameters(encodedQuery)
        val looseFragmentParameters = parseLooseParameters(encodedFragment)
        val safeQueryParameter: (String) -> String? = { key ->
            try {
                queryParameter(key)
            } catch (error: RuntimeException) {
                throw MalformedSubscriptionLinkException(error)
            }
        }

        val link = sequenceOf(
            directUrlKeys.firstNotNullOfOrNull { key -> normalizeSubscriptionUrl(safeQueryParameter(key)) },
            directUrlKeys.firstNotNullOfOrNull { key -> normalizeSubscriptionUrl(looseFragmentParameters[key]) },
            directUrlKeys.firstNotNullOfOrNull { key -> normalizeSubscriptionUrl(looseQueryParameters[key]) },
            normalizeSubscriptionUrl(encodedPath?.removePrefix("/")),
            normalizeSubscriptionUrl(encodedFragment?.takeIf { '=' !in it && '&' !in it }),
            normalizeSubscriptionUrl(encodedQuery?.takeIf { '=' !in it && '&' !in it }),
        ).firstNotNullOfOrNull { it } ?: return null

        val explicitName = sequenceOf(
            displayNameKeys.firstNotNullOfOrNull { key -> safeQueryParameter(key)?.trim()?.takeIf { it.isNotEmpty() } },
            displayNameKeys.firstNotNullOfOrNull { key -> looseFragmentParameters[key]?.trim()?.takeIf { it.isNotEmpty() } },
            displayNameKeys.firstNotNullOfOrNull { key -> looseQueryParameters[key]?.trim()?.takeIf { it.isNotEmpty() } },
        ).firstNotNullOfOrNull { it }

        return ProxyGroup(
            type = GroupType.SUBSCRIPTION,
            name = explicitName ?: inferNameFromUrl(link),
            subscription = SubscriptionBean().apply {
                this.link = link
            },
        )
    }

    fun fallbackGroupName(group: ProxyGroup, nowMillis: Long): String {
        return group.name?.takeIf { !it.isNullOrBlank() }
            ?: inferNameFromUrl(group.subscription?.link)
            ?: group.subscription?.token?.takeIf { !it.isNullOrBlank() }
            ?: "Subscription #$nowMillis"
    }

    private fun parseLooseParameters(raw: String?): Map<String, String> {
        val candidate = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        if ('=' !in candidate) return emptyMap()
        return candidate.split('&').mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val key = decodeComponent(entry.substring(0, separator)).trim().lowercase(Locale.US)
            val value = decodeComponent(entry.substring(separator + 1)).trim()
            key.takeIf { it.isNotEmpty() && value.isNotEmpty() }?.let { it to value }
        }.toMap()
    }

    private fun normalizeSubscriptionUrl(raw: String?): String? {
        val decoded = raw?.trim()?.takeIf { it.isNotEmpty() }?.let(::decodeComponent) ?: return null
        val parsed = decoded.toHttpUrlOrNull() ?: return null
        return parsed.toString()
    }

    private fun inferNameFromUrl(url: String?): String? {
        val host = url?.toHttpUrlOrNull()?.host ?: return null
        return host.removePrefix("www.").takeIf { it.isNotBlank() }
    }

    private fun decodeComponent(value: String): String {
        return try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (error: IllegalArgumentException) {
            throw MalformedSubscriptionLinkException(error)
        }
    }
}

internal class MalformedSubscriptionLinkException(cause: Throwable) :
    IllegalArgumentException("Malformed subscription link", cause)
