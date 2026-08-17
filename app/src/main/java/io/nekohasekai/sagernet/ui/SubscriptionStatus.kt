package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.SubscriptionBean

/** Source used to derive the traffic figures displayed for a subscription. */
enum class SubscriptionTrafficSource {
    STRUCTURED,
    USER_INFO,
}

data class SubscriptionTraffic(
    val usedBytes: Long,
    val remainingBytes: Long?,
    val totalBytes: Long?,
    val source: SubscriptionTrafficSource,
)

enum class SubscriptionExpiryState {
    UNKNOWN,
    ACTIVE,
    EXPIRED,
}

/**
 * UI-independent subscription metadata derived from [SubscriptionBean].
 *
 * Epoch values are expressed in seconds, matching the persisted bean and
 * Subscription-Userinfo header values.
 */
data class SubscriptionStatus(
    val traffic: SubscriptionTraffic?,
    val lastUpdatedEpochSeconds: Long?,
    val expiresAtEpochSeconds: Long?,
    val expiryState: SubscriptionExpiryState,
) {
    val isExpired: Boolean
        get() = expiryState == SubscriptionExpiryState.EXPIRED

    companion object {
        fun from(
            subscription: SubscriptionBean,
            nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
        ): SubscriptionStatus = SubscriptionStatusResolver.resolve(subscription, nowEpochSeconds)
    }
}

private object SubscriptionStatusResolver {

    private val uploadRegex = Regex("upload=([0-9]+)")
    private val downloadRegex = Regex("download=([0-9]+)")
    private val totalRegex = Regex("total=([0-9]+)")
    private val expireRegex = Regex("expire=([0-9]+)")

    fun resolve(subscription: SubscriptionBean, nowEpochSeconds: Long): SubscriptionStatus {
        val userInfo = parseUserInfo(subscription.subscriptionUserinfo)
        val traffic = structuredTraffic(subscription) ?: userInfo.traffic
        val expiresAt = subscription.expiryDate
            ?.toLong()
            ?.takeIf { it > 0L }
            ?: userInfo.expiresAtEpochSeconds

        return SubscriptionStatus(
            traffic = traffic,
            lastUpdatedEpochSeconds = subscription.lastUpdated
                ?.toLong()
                ?.takeIf { it > 0L },
            expiresAtEpochSeconds = expiresAt,
            expiryState = when {
                expiresAt == null -> SubscriptionExpiryState.UNKNOWN
                expiresAt <= nowEpochSeconds -> SubscriptionExpiryState.EXPIRED
                else -> SubscriptionExpiryState.ACTIVE
            },
        )
    }

    private fun structuredTraffic(subscription: SubscriptionBean): SubscriptionTraffic? {
        val used = subscription.bytesUsed?.takeIf { it > 0L } ?: return null
        val remaining = subscription.bytesRemaining?.takeIf { it > 0L }
        return SubscriptionTraffic(
            usedBytes = used,
            remainingBytes = remaining,
            totalBytes = remaining?.let { safeAdd(used, it) },
            source = SubscriptionTrafficSource.STRUCTURED,
        )
    }

    private fun parseUserInfo(value: String?): ParsedUserInfo {
        if (value.isNullOrBlank()) return ParsedUserInfo()

        val upload = uploadRegex.firstLong(value) ?: 0L
        val download = downloadRegex.firstLong(value) ?: 0L
        val used = safeAdd(upload, download)
        val total = totalRegex.firstLong(value) ?: 0L
        val traffic = if (used > 0L || total > 0L) {
            SubscriptionTraffic(
                usedBytes = used,
                remainingBytes = total.takeIf { it > 0L }?.let {
                    (it - used).coerceAtLeast(0L)
                },
                totalBytes = total.takeIf { it > 0L },
                source = SubscriptionTrafficSource.USER_INFO,
            )
        } else {
            null
        }

        return ParsedUserInfo(
            traffic = traffic,
            expiresAtEpochSeconds = expireRegex.firstLong(value)?.takeIf { it > 0L },
        )
    }

    private fun Regex.firstLong(value: String): Long? =
        find(value)?.groupValues?.getOrNull(1)?.toLongOrNull()

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private data class ParsedUserInfo(
        val traffic: SubscriptionTraffic? = null,
        val expiresAtEpochSeconds: Long? = null,
    )
}
