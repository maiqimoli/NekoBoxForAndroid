package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.ktx.MAX_SUBSCRIPTION_PROFILES
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RawUpdaterParsingTest {

    @Test
    fun `standalone Hysteria 2 YAML is imported`() = runBlocking {
        val bean = RawUpdater.parseRaw(
            """
            server: edge.example:443,8443-8450
            auth: token
            tls:
              sni: sni.example
            """.trimIndent(),
        )?.single() as HysteriaBean

        assertEquals(2, bean.protocolVersion.toInt())
        assertEquals("edge.example", bean.serverAddress)
        assertEquals("443,8443-8450", bean.serverPorts)
        assertEquals("token", bean.authPayload)
        assertEquals("sni.example", bean.sni)
    }

    @Test
    fun `standalone Hysteria 2 JSON is imported`() = runBlocking {
        val bean = RawUpdater.parseRaw(
            """
            {
              "type": "hysteria2",
              "server": "2001:db8::42",
              "server_port": 443,
              "password": "token"
            }
            """.trimIndent(),
        )?.single() as HysteriaBean

        assertEquals(2, bean.protocolVersion.toInt())
        assertEquals("2001:db8::42", bean.serverAddress)
        assertEquals("443", bean.serverPorts)
        assertEquals("token", bean.authPayload)
    }

    @Test
    fun `WireGuard peer count is bounded before profile creation`() {
        val config = buildString {
            appendLine("[Interface]")
            appendLine("Address = 10.0.0.1/32")
            appendLine("PrivateKey = local-key")
            repeat(MAX_SUBSCRIPTION_PROFILES + 1) {
                appendLine("[Peer]")
                appendLine("Endpoint = edge.example:443")
                appendLine("PublicKey = peer-key")
            }
        }

        assertThrows(SubscriptionParseLimitException::class.java) {
            RawUpdater.parseWireGuard(config)
        }
    }

    @Test
    fun `YAML event budget rejects a map before decoded tree validation`() {
        val yaml = "one: 1\ntwo: 2"

        assertEquals(
            2,
            RawUpdater.loadSubscriptionYamlMap(
                yaml,
                eventBudget = SubscriptionParseBudget(maxNodes = 5),
            ).size,
        )
        assertThrows(SubscriptionParseLimitException::class.java) {
            RawUpdater.loadSubscriptionYamlMap(
                yaml,
                eventBudget = SubscriptionParseBudget(maxNodes = 4),
            )
        }
    }

    @Test
    fun `JSON tokener stops a flat array while it is being parsed`() {
        val json = "[1,2,3]"

        assertEquals(
            3,
            (RawUpdater.parseJsonValueWithBudget(
                json,
                SubscriptionParseBudget(maxNodes = 4),
            ) as JSONArray).length(),
        )
        assertThrows(SubscriptionParseLimitException::class.java) {
            RawUpdater.parseJsonValueWithBudget(
                json,
                SubscriptionParseBudget(maxNodes = 3),
            )
        }
    }

    @Test
    fun `nested JSON arrays share one profile allocation budget`() {
        val profiles = JSONArray().put(
            JSONArray().apply {
                repeat(3) { index -> put(genericProfile(index)) }
            },
        )

        assertThrows(SubscriptionParseLimitException::class.java) {
            RawUpdater.parseJSON(profiles, SubscriptionParseBudget(maxProfiles = 2))
        }
    }

    @Test
    fun `JSON outbounds are bounded before ConfigBean allocation`() {
        val outbounds = JSONArray().apply {
            repeat(3) { index ->
                put(JSONObject().put("type", "socks").put("tag", "profile-$index"))
            }
        }

        assertThrows(SubscriptionParseLimitException::class.java) {
            RawUpdater.parseJSON(
                JSONObject().put("outbounds", outbounds),
                SubscriptionParseBudget(maxProfiles = 2),
            )
        }
    }

    private fun genericProfile(index: Int) = JSONObject()
        .put("server", "edge-$index.example")
        .put("server_port", 443)
}
