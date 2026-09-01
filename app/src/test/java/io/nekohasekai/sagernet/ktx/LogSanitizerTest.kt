package io.nekohasekai.sagernet.ktx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSanitizerTest {

    @Test
    fun redactsUrlUserInfoAndQuerySecrets() {
        val redacted = redactSensitiveData(
            "GET https://alice:secret@example.com/sub?token=top-secret&uuid=device-id",
        )

        assertFalse(redacted.contains("alice"))
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("top-secret"))
        assertFalse(redacted.contains("device-id"))
        assertTrue(redacted.contains("https://[REDACTED]@example.com"))
    }

    @Test
    fun redactsJsonAndHeaderSecretsCaseInsensitively() {
        val redacted = redactSensitiveData(
            """{"password":"hunter2","private_key":"key-data","PSK":'shared'}
                Authorization: Bearer bearer-value""".trimIndent(),
        )

        listOf("hunter2", "key-data", "shared", "bearer-value").forEach {
            assertFalse(redacted.contains(it))
        }
        assertTrue(redacted.contains("\"password\":\"[REDACTED]\""))
    }

    @Test
    fun redactsPemPrivateKeysWithoutChangingOrdinaryFields() {
        val redacted = redactSensitiveData(
            "name=profile\n-----BEGIN PRIVATE KEY-----\nsecret-material\n" +
                "-----END PRIVATE KEY-----\nserver=example.com",
        )

        assertFalse(redacted.contains("secret-material"))
        assertTrue(redacted.contains("name=profile"))
        assertTrue(redacted.contains("server=example.com"))
    }
}
