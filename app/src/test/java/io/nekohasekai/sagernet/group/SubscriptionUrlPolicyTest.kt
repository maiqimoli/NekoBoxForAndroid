package io.nekohasekai.sagernet.group

import org.junit.Assert.assertThrows
import org.junit.Test

class SubscriptionUrlPolicyTest {

    @Test
    fun acceptsHttpsAndExplicitLoopbackHttp() {
        requireSecureSubscriptionUrl("https://example.com/subscription")
        requireSecureSubscriptionUrl("http://localhost:8080/subscription")
        requireSecureSubscriptionUrl("http://127.1.2.3/subscription")
        requireSecureSubscriptionUrl("http://[::1]:8080/subscription")
    }

    @Test
    fun rejectsRemoteHttpAndLookalikeHosts() {
        listOf(
            "http://example.com/subscription",
            "http://localhost.example.com/subscription",
            "http://127.0.0.1.example.com/subscription",
            "ftp://example.com/subscription",
            "https:///missing-host",
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                requireSecureSubscriptionUrl(value)
            }
        }
    }
}
