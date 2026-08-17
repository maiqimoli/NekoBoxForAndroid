package io.nekohasekai.sagernet.utils

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

object ExitLocationResolver {

    internal data class ExitLocation(
        val ip: String,
        val city: String? = null,
        val region: String? = null,
        val countryCode: String? = null,
        val isp: String? = null,
    ) {
        fun displayLabel(): String {
            val location = listOfNotNull(city, region, countryCode)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinctBy { it.lowercase() }
            return (listOf(ip) + location + listOfNotNull(isp)).joinToString(" · ")
        }
    }

    fun resolve(proxyPort: Int): String {
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxyPort)))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()

        val location = runCatching {
            parseGeoJson(request(client, "https://api.ip.sb/geoip"))
        }.getOrNull() ?: runCatching {
            parseCloudflareTrace(request(client, "https://www.cloudflare.com/cdn-cgi/trace"))
        }.getOrNull()

        return requireNotNull(location).displayLabel()
    }

    private fun request(client: OkHttpClient, url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain;q=0.9")
            .header("User-Agent", "NekoBoxForAndroid")
            .build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            response.body.string()
        }
    }

    internal fun parseGeoJson(value: String): ExitLocation? {
        val json = JsonParser.parseString(value).asJsonObject
        fun text(key: String) = json.get(key)
            ?.takeUnless { it.isJsonNull }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("null", true) }

        val ip = text("ip") ?: return null
        return ExitLocation(
            ip = ip,
            city = text("city"),
            region = text("region"),
            countryCode = text("country_code")?.uppercase(),
            isp = text("isp") ?: text("organization") ?: text("asn_organization"),
        )
    }

    internal fun parseCloudflareTrace(value: String): ExitLocation? {
        val fields = value.lineSequence().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to
                    line.substring(separator + 1).trim()
        }.toMap()
        val ip = fields["ip"]?.takeIf(String::isNotBlank) ?: return null
        return ExitLocation(
            ip = ip,
            countryCode = fields["loc"]?.takeIf(String::isNotBlank)?.uppercase(),
        )
    }
}
