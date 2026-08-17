package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity

internal const val LATENCY_SAMPLE_COUNT = 3

internal fun medianLatency(samples: Collection<Int>): Int {
    require(samples.isNotEmpty()) { "Latency samples must not be empty" }
    val sorted = samples.sorted()
    return sorted[sorted.size / 2]
}

class UrlTest {

    val link = DataStore.connectionTestURL
    private val timeout = 5000

    suspend fun doTest(profile: ProxyEntity): Int {
        return TestInstance(profile, link, timeout).doTest()
    }

}
