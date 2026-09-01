package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.ProxyEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private fun Throwable?.mergeProxyCloseFailure(next: Throwable): Throwable =
    this?.also { current ->
        if (current !== next) current.addSuppressed(next)
    } ?: next

class ProxyInstance(
    profile: ProxyEntity,
    var service: BaseService.Interface? = null,
    initialDisplayProfileName: String = profile.displayName(),
) : BoxInstance(profile) {

    var lastSelectorGroupId = -1L
    var displayProfileName = initialDisplayProfileName

    // Create the controller before initialization so service-side resets can serialize with it.
    val looper = service?.let { TrafficLooper(it.data, it.data.binder) }

    private val closeMutex = Mutex()

    @Volatile
    private var proxyClosed = false

    override fun buildConfig() {
        super.buildConfig()
        lastSelectorGroupId = super.config.selectorGroupId
    }

    // only use this in temporary instance
    fun buildConfigTmp() {
        buildConfig()
    }

    override suspend fun init() {
        val trafficLooper = looper
        if (trafficLooper == null) {
            super.init()
        } else {
            trafficLooper.withInitializationLock { super.init() }
        }
    }

    @Synchronized
    override fun launch() {
        check(!proxyClosed) { "Proxy instance is already closed" }
        box.setAsMain()
        super.launch() // start box
        looper?.start()
    }

    override suspend fun closeAndWait(): Unit = closeMutex.withLock {
        if (proxyClosed) return@withLock

        var failure: Throwable? = null
        try {
            looper?.stop()
        } catch (error: Throwable) {
            failure = failure.mergeProxyCloseFailure(error)
        }
        try {
            super.closeAndWait()
        } catch (error: Throwable) {
            failure = failure.mergeProxyCloseFailure(error)
        }
        proxyClosed = true

        failure?.let { throw it }
    }
}
