package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import kotlin.coroutines.coroutineContext

class TestInstance(profile: ProxyEntity, val link: String, private val timeout: Int) :
    BoxInstance(profile) {

    suspend fun doTest(): Int = withContext(Dispatchers.IO) {
        this@TestInstance.use {
            processes = GuardedProcessPool {
                Logs.w(it)
            }
            coroutineContext.ensureActive()
            init()
            coroutineContext.ensureActive()
            launch()
            if (processes.processCount > 0) {
                // wait for plugin start
                delay(500)
            }
            val samples = ArrayList<Int>(LATENCY_SAMPLE_COUNT)
            repeat(LATENCY_SAMPLE_COUNT) { index ->
                coroutineContext.ensureActive()
                samples += Libcore.urlTest(box, link, timeout)
                coroutineContext.ensureActive()
                if (index < LATENCY_SAMPLE_COUNT - 1) delay(75L)
            }
            medianLatency(samples)
        }
    }

    override fun buildConfig() {
        config = buildConfig(profile, true)
    }

    override suspend fun loadConfig() {
        // don't call destroyAllJsi here
        if (BuildConfig.DEBUG) Logs.d(config.config)
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

}
