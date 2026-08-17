package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExitLocationResolverTest {

    @Test
    fun parsesGeoJsonAndBuildsCompactLabel() {
        val result = ExitLocationResolver.parseGeoJson(
            """{"ip":"203.0.113.8","city":"Hong Kong","region":"Hong Kong","country_code":"hk","isp":"Example Net"}"""
        )

        assertEquals("Example Net", result?.isp)
        assertEquals("203.0.113.8 · Hong Kong · HK · Example Net", result?.displayLabel())
    }

    @Test
    fun parsesCloudflareFallback() {
        val result = ExitLocationResolver.parseCloudflareTrace(
            "fl=29f\nip=2001:db8::1\nloc=JP\ntls=TLSv1.3\n"
        )

        assertEquals("2001:db8::1 · JP", result?.displayLabel())
    }

    @Test
    fun rejectsPayloadWithoutIp() {
        assertNull(ExitLocationResolver.parseGeoJson("""{"country_code":"US"}"""))
        assertNull(ExitLocationResolver.parseCloudflareTrace("loc=US\n"))
    }
}
