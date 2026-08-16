package io.nekohasekai.sagernet.fmt.socks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SOCKSFmtTest {

    @Test
    fun parsePlainSOCKSParsesHostCredentials() {
        val bean = requireNotNull(parsePlainSOCKS("proxy.example.com:1080:user:pass"))

        assertEquals("proxy.example.com", bean.serverAddress)
        assertEquals(1080, bean.serverPort)
        assertEquals("user", bean.username)
        assertEquals("pass", bean.password)
    }

    @Test
    fun parsePlainSOCKSParsesBracketedIPv6Host() {
        val bean = requireNotNull(parsePlainSOCKS("[2001:db8::1]:1080:user:pass"))

        assertEquals("2001:db8::1", bean.serverAddress)
        assertEquals(1080, bean.serverPort)
        assertEquals("user", bean.username)
        assertEquals("pass", bean.password)
    }

    @Test
    fun parsePlainSOCKSAllowsColonInPassword() {
        val bean = requireNotNull(parsePlainSOCKS("proxy.example.com:1080:user:pa:ss"))

        assertEquals("pa:ss", bean.password)
    }

    @Test
    fun parsePlainSOCKSRejectsInvalidValues() {
        assertNull(parsePlainSOCKS("proxy.example.com:0:user:pass"))
        assertNull(parsePlainSOCKS("proxy.example.com:70000:user:pass"))
        assertNull(parsePlainSOCKS("proxy.example.com:1080::pass"))
        assertNull(parsePlainSOCKS("proxy.example.com:1080:user:"))
        assertNull(parsePlainSOCKS("[2001:db8::1:1080:user:pass"))
    }
}
