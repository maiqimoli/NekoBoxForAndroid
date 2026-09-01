package moe.matsuri.nb4a.plugin

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Build
import android.widget.Toast
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.plugin.PluginManager.loadString
import io.nekohasekai.sagernet.plugin.PluginSecurity
import io.nekohasekai.sagernet.utils.PackageCache

object Plugins {
    const val AUTHORITIES_PREFIX_SEKAI_EXE = "io.nekohasekai.sagernet.plugin."
    const val AUTHORITIES_PREFIX_NEKO_EXE = "moe.matsuri.exe."

    const val ACTION_NATIVE_PLUGIN = "io.nekohasekai.sagernet.plugin.ACTION_NATIVE_PLUGIN"

    const val METADATA_KEY_ID = "io.nekohasekai.sagernet.plugin.id"
    const val METADATA_KEY_EXECUTABLE_PATH = "io.nekohasekai.sagernet.plugin.executable_path"

    fun isExe(pkg: PackageInfo): Boolean {
        val packageName = pkg.packageName ?: return false
        val applicationInfo = pkg.applicationInfo ?: return false
        return applicationInfo.packageName == packageName && applicationInfo.enabled &&
                pkg.providers.orEmpty().any { isUsableExeProvider(it, packageName) }
    }

    fun isExeProvider(provider: ProviderInfo): Boolean {
        val auth = provider.authority ?: return false
        return auth.startsWith(AUTHORITIES_PREFIX_SEKAI_EXE)
                || auth.startsWith(AUTHORITIES_PREFIX_NEKO_EXE)
    }

    fun isUsableExeProvider(provider: ProviderInfo, expectedPackageName: String): Boolean {
        val applicationInfo = provider.applicationInfo ?: return false
        return provider.exported && provider.enabled &&
                provider.packageName == expectedPackageName &&
                applicationInfo.packageName == expectedPackageName &&
                applicationInfo.enabled && isExeProvider(provider)
    }

    fun preferExePrefix(): String {
        return AUTHORITIES_PREFIX_NEKO_EXE
    }

    fun isUsingMatsuriExe(pluginId: String): Boolean {
        getPlugin(pluginId)?.apply {
            if (authority.startsWith(AUTHORITIES_PREFIX_NEKO_EXE)) {
                return true
            }
        }
        return false;
    }

    fun displayExeProvider(pkgName: String): String {
        return if (pkgName.startsWith(AUTHORITIES_PREFIX_SEKAI_EXE)) {
            "SagerNet"
        } else if (pkgName.startsWith(AUTHORITIES_PREFIX_NEKO_EXE)) {
            "Matsuri"
        } else {
            "Unknown"
        }
    }

    fun getPlugin(pluginId: String, requireRoot: Boolean = false): ProviderInfo? {
        if (pluginId.isBlank()) return null
        getPluginExternal(pluginId)?.let { external ->
            val trustedForRoot = if (requireRoot) {
                PackageCache.resolveTrustedPluginProvider(external)?.trustedForRoot == true
            } else {
                false
            }
            if (PluginSecurity.mayUseExternalPlugin(requireRoot, trustedForRoot)) return external
        }
        // internal so
        return ProviderInfo().apply { authority = AUTHORITIES_PREFIX_NEKO_EXE }
    }

    fun getPluginExternal(pluginId: String): ProviderInfo? {
        if (pluginId.isBlank()) return null

        // try queryIntentContentProviders
        var providers = getExtPluginOld(pluginId)

        // try PackageCache
        if (providers.isEmpty()) providers = getExtPluginNew(pluginId)

        // not found
        if (providers.isEmpty()) return null

        if (providers.size > 1) {
            val prefer = providers.filter {
                it.authority.startsWith(preferExePrefix())
            }
            if (prefer.size == 1) providers = prefer
        }

        if (providers.size > 1) {
            val message =
                "Conflicting plugins found from: ${providers.joinToString { it.packageName }}"
            Toast.makeText(SagerNet.application, message, Toast.LENGTH_LONG).show()
        }

        return providers[0]
    }

    private fun getExtPluginNew(pluginId: String): List<ProviderInfo> {
        PackageCache.awaitLoadSync()
        return (PackageCache.installedPluginPackages + PackageCache.rejectedPluginPackages)
            .values.filter(PackageCache::isTrustedPluginPackage).flatMap { pkg ->
                pkg.providers.orEmpty().filter { provider ->
                    isUsableExeProvider(provider, pkg.packageName) &&
                            provider.loadString(METADATA_KEY_ID) == pluginId
                }
            }
    }

    fun getRejectedPluginPackages(pluginId: String): List<String> {
        if (pluginId.isBlank()) return emptyList()
        PackageCache.awaitLoadSync()
        val rejectedFromCache =
            (PackageCache.installedPluginPackages + PackageCache.rejectedPluginPackages)
                .values.filterNot(PackageCache::isTrustedPluginPackage).filter { pkg ->
                    pkg.providers.orEmpty().any { provider ->
                        isUsableExeProvider(provider, pkg.packageName) &&
                                provider.loadString(METADATA_KEY_ID) == pluginId
                    }
                }.map { it.packageName }
        val rejectedFromQuery = queryExtPluginOld(pluginId)
            .filterNot(PackageCache::isTrustedPluginProvider)
            .map { it.packageName }
        return (rejectedFromCache + rejectedFromQuery).distinct()
    }

    private fun buildUri(id: String, auth: String) = Uri.Builder()
        .scheme("plugin")
        .authority(auth)
        .path("/$id")
        .build()

    private fun getExtPluginOld(pluginId: String): List<ProviderInfo> {
        return queryExtPluginOld(pluginId).filter(PackageCache::isTrustedPluginProvider)
    }

    private fun queryExtPluginOld(pluginId: String): List<ProviderInfo> {
        var flags = PackageManager.GET_META_DATA
        if (Build.VERSION.SDK_INT >= 24) {
            flags =
                flags or PackageManager.MATCH_DIRECT_BOOT_UNAWARE or PackageManager.MATCH_DIRECT_BOOT_AWARE
        }
        val list1 = runCatching {
            SagerNet.application.packageManager.queryIntentContentProviders(
                Intent(ACTION_NATIVE_PLUGIN, buildUri(pluginId, "io.nekohasekai.sagernet")), flags
            )
        }.getOrDefault(emptyList())
        val list2 = runCatching {
            SagerNet.application.packageManager.queryIntentContentProviders(
                Intent(ACTION_NATIVE_PLUGIN, buildUri(pluginId, "moe.matsuri.lite")), flags
            )
        }.getOrDefault(emptyList())
        return (list1 + list2).mapNotNull {
            it.providerInfo
        }.filter { provider ->
            val packageName = provider.packageName ?: return@filter false
            isUsableExeProvider(provider, packageName)
        }
    }
}
