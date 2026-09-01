package io.nekohasekai.sagernet.plugin

import android.content.pm.ComponentInfo
import android.content.pm.ProviderInfo
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.utils.PackageCache
import moe.matsuri.nb4a.plugin.Plugins
import java.io.File
import java.io.FileNotFoundException

object PluginManager {

    class PluginNotFoundException(val plugin: String) : FileNotFoundException(plugin),
        BaseService.ExpectedException {
        override fun getLocalizedMessage() =
            SagerNet.application.getString(R.string.plugin_unknown, plugin)
    }

    class PluginUntrustedException(val plugin: String, val packages: List<String>) : SecurityException(),
        BaseService.ExpectedException {
        override fun getLocalizedMessage() = SagerNet.application.getString(
            R.string.plugin_untrusted_error,
            packages.joinToString(),
        )
    }

    data class InitResult(
        val path: String,
        val info: ProviderInfo,
        val trustedForRoot: Boolean,
    )

    @Throws(Throwable::class)
    fun init(pluginId: String, requireRoot: Boolean = false): InitResult? {
        if (pluginId.isEmpty()) return null
        var throwable: Throwable? = null

        try {
            val result = initNative(pluginId, requireRoot)
            if (result != null) return result
        } catch (t: Throwable) {
            throwable = t
            Logs.w(t)
        }

        if (throwable != null) throw throwable
        val rejectedPackages = Plugins.getRejectedPluginPackages(pluginId)
        throw if (rejectedPackages.isNotEmpty()) PluginUntrustedException(pluginId, rejectedPackages)
        else PluginNotFoundException(pluginId)
    }

    private fun initNative(pluginId: String, requireRoot: Boolean): InitResult? {
        val info = Plugins.getPlugin(pluginId, requireRoot) ?: return null

        // internal so
        if (info.applicationInfo == null) {
            try {
                initNativeInternal(pluginId)?.let { return InitResult(it, info, true) }
            } catch (t: Throwable) {
                Logs.w("initNativeInternal failed", t)
            }
            return null
        }

        try {
            initNativeFaster(pluginId, info)?.let { return it }
        } catch (t: PluginUntrustedException) {
            throw t
        } catch (t: Throwable) {
            Logs.w("initNativeFaster failed", t)
        }

        Logs.w("Init native returns empty result")
        return null
    }

    private fun initNativeInternal(pluginId: String): String? {
        fun soIfExist(soName: String): String? {
            return runCatching {
                resolveExecutable(SagerNet.application.applicationInfo.nativeLibraryDir, soName)
            }.getOrNull()
        }
        return when (pluginId) {
            "hysteria-plugin" -> soIfExist("libhysteria.so")
            "hysteria2-plugin" -> soIfExist("libhysteria2.so")
            else -> null
        }
    }

    private fun initNativeFaster(pluginId: String, discoveredProvider: ProviderInfo): InitResult? {
        val verified = PackageCache.resolveTrustedPluginProvider(discoveredProvider)
            ?: throw PluginUntrustedException(
                pluginId,
                listOfNotNull(discoveredProvider.packageName),
            )
        val provider = verified.provider
        if (provider.loadString(Plugins.METADATA_KEY_ID) != pluginId) return null
        val relativePath = provider.loadString(Plugins.METADATA_KEY_EXECUTABLE_PATH) ?: return null
        val nativeLibraryDir = provider.applicationInfo?.nativeLibraryDir ?: return null
        return InitResult(
            resolveExecutable(nativeLibraryDir, relativePath),
            provider,
            verified.trustedForRoot,
        )
    }

    internal fun resolveExecutable(nativeLibraryDir: String, relativePath: String): String {
        require(relativePath.isNotBlank()) { "Plugin executable path is empty" }
        val libraryDir = File(nativeLibraryDir).canonicalFile
        val executable = File(libraryDir, relativePath).canonicalFile
        val directoryPrefix = libraryDir.path.trimEnd(File.separatorChar) + File.separator
        check(executable.path.startsWith(directoryPrefix)) {
            "Plugin executable must be inside nativeLibraryDir"
        }
        check(executable.isFile && executable.canExecute()) {
            "Plugin executable is missing or is not executable"
        }
        return executable.path
    }

    @Suppress("DEPRECATION") // Bundle.get 为 Java 弃用 API，无兼容替代
    fun ComponentInfo.loadString(key: String): String? = runCatching {
        when (val value = metaData?.get(key)) {
            is String -> value
            is Int -> SagerNet.application.packageManager
                .getResourcesForApplication(applicationInfo)
                .getString(value)
            else -> null
        }
    }.getOrNull()
}
