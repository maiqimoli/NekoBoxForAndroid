package io.nekohasekai.sagernet.fmt.hysteria

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml

class HysteriaConfigParsingTest {

    @Test
    fun `official Hysteria 2 YAML fields are imported`() {
        val bean = requireNotNull(
            parseYaml(
                """
                server: "[2001:db8::42]:443,8443-8450"
                auth: token
                bandwidth:
                  up: 1.5 gbps
                  down: 250 mbps
                tls:
                  sni: edge.example
                  insecure: true
                obfs:
                  type: salamander
                  salamander:
                    password: mask
                transport:
                  type: udp
                  udp:
                    hopInterval: 30s
                """.trimIndent(),
            ),
        )

        assertEquals(2, bean.protocolVersion.toInt())
        assertEquals("2001:db8::42", bean.serverAddress)
        assertEquals("443,8443-8450", bean.serverPorts)
        assertEquals("token", bean.authPayload)
        assertEquals(1_500, bean.uploadMbps.toInt())
        assertEquals(250, bean.downloadMbps.toInt())
        assertEquals("edge.example", bean.sni)
        assertTrue(bean.allowInsecure)
        assertEquals("mask", bean.obfuscation)
        assertEquals(30, bean.hopInterval.toInt())
    }

    @Test
    fun `equivalent Hysteria 2 JSON fields are imported`() {
        val bean = requireNotNull(
            JSONObject(
                """
                {
                  "server": "edge.example",
                  "server_ports": [443, "8443-8450"],
                  "auth": "token",
                  "bandwidth": {"up": "1.5 gbps", "down": "250 mbps"},
                  "tls": {"sni": "sni.example", "insecure": false},
                  "obfs": {"type": "salamander", "password": "mask"},
                  "transport": {"udp": {"hopInterval": "45s"}}
                }
                """.trimIndent(),
            ).parseHysteriaJson(),
        )

        assertEquals(2, bean.protocolVersion.toInt())
        assertEquals("edge.example", bean.serverAddress)
        assertEquals("443,8443-8450", bean.serverPorts)
        assertEquals("token", bean.authPayload)
        assertEquals(1_500, bean.uploadMbps.toInt())
        assertEquals(250, bean.downloadMbps.toInt())
        assertEquals("sni.example", bean.sni)
        assertFalse(bean.allowInsecure)
        assertEquals("mask", bean.obfuscation)
        assertEquals(45, bean.hopInterval.toInt())
    }

    @Test
    fun `minimal server and auth config is recognized as Hysteria 2`() {
        val bean = requireNotNull(
            mapOf("server" to "edge.example:8443", "auth" to "token")
                .parseHysteriaConfig(),
        )

        assertEquals(2, bean.protocolVersion.toInt())
        assertEquals("edge.example", bean.serverAddress)
        assertEquals("8443", bean.serverPorts)
        assertEquals("token", bean.authPayload)
    }

    @Test
    fun `raw IPv6 endpoint keeps its final hextet`() {
        val bean = requireNotNull(
            mapOf("type" to "hysteria2", "server" to "2001:db8::42", "auth" to "token")
                .parseHysteriaConfig(),
        )

        assertEquals("2001:db8::42", bean.serverAddress)
        assertEquals("443", bean.serverPorts)
    }

    @Test
    fun `explicit port replaces an embedded endpoint port`() {
        val bean = requireNotNull(
            mapOf(
                "type" to "hysteria2",
                "server" to "edge.example:4443",
                "server_port" to 8443,
            ).parseHysteriaConfig(),
        )

        assertEquals("edge.example", bean.serverAddress)
        assertEquals("8443", bean.serverPorts)
    }

    @Test
    fun `legacy Hysteria 1 config remains supported`() {
        val bean = requireNotNull(
            mapOf(
                "server" to "legacy.example:443",
                "up_mbps" to 80,
                "down_mbps" to 160,
                "auth_str" to "legacy-token",
                "protocol" to "faketcp",
                "server_name" to "legacy-sni.example",
            ).parseHysteriaConfig(),
        )

        assertEquals(1, bean.protocolVersion.toInt())
        assertEquals(80, bean.uploadMbps.toInt())
        assertEquals(160, bean.downloadMbps.toInt())
        assertEquals(HysteriaBean.TYPE_STRING, bean.authPayloadType.toInt())
        assertEquals("legacy-token", bean.authPayload)
        assertEquals(HysteriaBean.PROTOCOL_FAKETCP, bean.protocol.toInt())
        assertEquals("legacy-sni.example", bean.sni)
    }

    @Test
    fun `password requires an explicit Hysteria 2 schema signal`() {
        assertNull(
            mapOf("server" to "generic.example", "password" to "secret")
                .parseHysteriaConfig(),
        )

        val bean = requireNotNull(
            mapOf(
                "type" to "hysteria2",
                "server" to "hy2.example",
                "server_port" to 7443,
                "password" to "secret",
            ).parseHysteriaConfig(),
        )
        assertEquals("secret", bean.authPayload)
        assertEquals("7443", bean.serverPorts)
    }

    @Test
    fun `unknown root type is not treated as Hysteria`() {
        assertNull(
            mapOf(
                "type" to "other-protocol",
                "server" to "generic.example:443",
                "auth" to "token",
                "tls" to mapOf("insecure" to true),
            ).parseHysteriaConfig(),
        )
    }

    @Test
    fun `invalid explicit version is not inferred from other fields`() {
        assertNull(
            mapOf(
                "server" to "edge.example:443",
                "version" to "garbage",
                "auth" to "token",
                "tls" to mapOf("insecure" to true),
            ).parseHysteriaConfig(),
        )
        assertNull(
            mapOf(
                "server" to "edge.example:443",
                "version" to 3,
                "auth" to "token",
            ).parseHysteriaConfig(),
        )
    }

    @Test
    fun `type and version conflict is rejected`() {
        assertNull(
            mapOf(
                "type" to "hysteria",
                "version" to 2,
                "server" to "edge.example:443",
                "auth" to "token",
            ).parseHysteriaConfig(),
        )
    }

    @Test
    fun `generic server and TLS object is not inferred as Hysteria 2`() {
        assertNull(
            mapOf(
                "server" to "edge.example",
                "server_port" to 443,
                "tls" to mapOf("enabled" to true),
            ).parseHysteriaConfig(),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseYaml(text: String): HysteriaBean? {
        return (Yaml().load(text) as Map<String, Any?>).parseHysteriaConfig()
    }
}
