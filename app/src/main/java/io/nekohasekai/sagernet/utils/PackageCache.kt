package io.nekohasekai.sagernet.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Build
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.listenForPackageChanges
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.plugin.PluginSecurity
import io.nekohasekai.sagernet.plugin.PluginTrustStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.plugin.Plugins
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object PackageCache {

    data class PluginTrustCandidate(
        val packageName: String,
        val label: String,
        val signerDigests: Set<String>,
    )

    data class VerifiedPluginProvider(
        val provider: ProviderInfo,
        val trustedForRoot: Boolean,
    )

    lateinit var installedPackages: Map<String, PackageInfo>
    lateinit var installedPluginPackages: Map<String, PackageInfo>
    lateinit var rejectedPluginPackages: Map<String, PackageInfo>
    lateinit var installedApps: Map<String, ApplicationInfo>
    lateinit var packageMap: Map<String, Int>
    val uidMap = HashMap<Int, HashSet<String>>()
    val loaded = Mutex(true)
    var registerd = AtomicBoolean(false)
    private val reloadScheduled = AtomicBoolean(false)

    // called from init (suspend)
    fun register() {
        if (registerd.getAndSet(true)) return
        reload()
        app.listenForPackageChanges(false) {
            if (!reloadScheduled.compareAndSet(false, true)) return@listenForPackageChanges
            runOnDefaultDispatcher {
                try {
                    reload()
                    labelMap.clear()
                } finally {
                    reloadScheduled.set(false)
                }
            }
        }
        loaded.unlock()
    }

    @SuppressLint("InlinedApi")
    fun reload() {
        val signingFlag = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val rawPackageInfo = app.packageManager.getInstalledPackages(
            PackageManager.MATCH_UNINSTALLED_PACKAGES
                    or PackageManager.GET_PERMISSIONS
                    or PackageManager.GET_PROVIDERS
                    or PackageManager.GET_META_DATA
                    or signingFlag
        )

        installedPackages = rawPackageInfo.filter {
            when (it.packageName) {
                "android" -> true
                else -> it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
            }
        }.associateBy { it.packageName }

        val pluginPackages = rawPackageInfo.filter {
            Plugins.isExe(it) && it.applicationInfo?.let { applicationInfo ->
                applicationInfo.enabled && applicationInfo.flags and ApplicationInfo.FLAG_INSTALLED != 0
            } == true
        }
        installedPluginPackages = pluginPackages.filter(::isTrustedPluginPackage)
            .associateBy { it.packageName }
        rejectedPluginPackages = pluginPackages.filterNot(::isTrustedPluginPackage)
            .associateBy { it.packageName }

        val installed = app.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        installedApps = installed.associateBy { it.packageName }
        packageMap = installed.associate { it.packageName to it.uid }
        uidMap.clear()
        for (info in installed) {
            val uid = info.uid
            uidMap.getOrPut(uid) { HashSet() }.add(info.packageName)
        }
    }

    operator fun get(uid: Int) = uidMap[uid]
    operator fun get(packageName: String) = packageMap[packageName]

    fun resolveTrustedPluginProvider(provider: ProviderInfo): VerifiedPluginProvider? {
        val packageName = provider.packageName?.takeIf { it.isNotBlank() } ?: return null
        val packageInfo = loadPackageForPlugin(packageName) ?: return null
        if (!isUsablePluginPackage(packageInfo)) return null
        val freshProvider = packageInfo.providers.orEmpty().firstOrNull { candidate ->
            candidate.packageName == packageName &&
                    candidate.name == provider.name &&
                    candidate.authority == provider.authority &&
                    Plugins.isUsableExeProvider(candidate, packageName)
        } ?: return null
        val trustLevel = pluginTrustLevel(packageInfo)
        if (!trustLevel.canExecute) return null
        return VerifiedPluginProvider(freshProvider, trustLevel.canExecuteAsRoot)
    }

    fun isTrustedPluginProvider(provider: ProviderInfo): Boolean =
        resolveTrustedPluginProvider(provider) != null

    fun isTrustedPluginPackage(packageInfo: PackageInfo): Boolean =
        isUsablePluginPackage(packageInfo) && pluginTrustLevel(packageInfo).canExecute

    private fun pluginTrustLevel(packageInfo: PackageInfo): PluginSecurity.TrustLevel {
        val packageName = packageInfo.packageName ?: return PluginSecurity.TrustLevel.NONE
        val hostSignatureMatches = runCatching {
            app.packageManager.checkSignatures(app.packageName, packageName) ==
                    PackageManager.SIGNATURE_MATCH
        }.getOrDefault(false)
        val currentDigests = currentSignerDigests(packageInfo)
        val allowedDigests = trustedPluginPackageSigners[packageName]
        val staticSignerMatches = currentDigests.isNotEmpty() && allowedDigests != null &&
                currentDigests.all(allowedDigests::contains)
        val userConfirmationMatches = !hostSignatureMatches && !staticSignerMatches &&
                PluginSecurity.isTrusted(PluginTrustStore.read(), packageName, currentDigests)
        return PluginSecurity.trustLevel(
            hostSignatureMatches,
            staticSignerMatches,
            userConfirmationMatches,
        )
    }

    fun getPluginTrustCandidate(packageName: String): PluginTrustCandidate? {
        val packageInfo = loadPackageForPlugin(packageName) ?: return null
        if (!isUsablePluginPackage(packageInfo)) return null
        val applicationInfo = packageInfo.applicationInfo ?: return null
        val signerDigests = currentSignerDigests(packageInfo)
        if (signerDigests.isEmpty()) return null
        return PluginTrustCandidate(
            packageName,
            applicationInfo.loadLabel(app.packageManager).toString(),
            signerDigests,
        )
    }

    @Synchronized
    fun trustPluginPackage(packageName: String, expectedSignerDigests: Set<String>): Boolean {
        val current = getPluginTrustCandidate(packageName) ?: return false
        if (current.signerDigests != expectedSignerDigests) return false
        return runCatching {
            PluginTrustStore.update { records ->
                PluginSecurity.trust(records, packageName, current.signerDigests)
            }
            true
        }.onFailure { error ->
            Logs.w("Failed to persist trust for plugin $packageName", error)
        }.getOrDefault(false)
    }

    private fun isUsablePluginPackage(packageInfo: PackageInfo): Boolean {
        val packageName = packageInfo.packageName ?: return false
        val applicationInfo = packageInfo.applicationInfo ?: return false
        return applicationInfo.packageName == packageName &&
                applicationInfo.enabled &&
                applicationInfo.flags and ApplicationInfo.FLAG_INSTALLED != 0 &&
                Plugins.isExe(packageInfo)
    }

    @SuppressLint("PackageManagerGetSignatures")
    private fun loadPackageForPlugin(packageName: String): PackageInfo? {
        val signingFlag = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        return runCatching {
            app.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA or signingFlag,
            )
        }.getOrNull()
    }

    private fun currentSignerDigests(packageInfo: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures.orEmpty()
        }
        return signatures.mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }

    private val trustedPluginPackageSigners: Map<String, Set<String>> by lazy {
        app.resources.getStringArray(R.array.trusted_plugin_package_signers)
            .mapNotNull { entry ->
                val packageName = entry.substringBefore('=').trim()
                val digest = entry.substringAfter('=', "").replace(":", "")
                    .trim().lowercase(Locale.ROOT)
                if (packageName.isBlank() || !digest.matches(Regex("[0-9a-f]{64}"))) null
                else packageName to digest
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, digests) -> digests.toSet() }
    }

    fun awaitLoadSync() {
        if (::packageMap.isInitialized) {
            return
        }
        if (!registerd.get()) {
            register()
            return
        }
        runBlocking {
            loaded.withLock {
                // just await
            }
        }
    }

    private val labelMap = mutableMapOf<String, String>()
    fun loadLabel(packageName: String): String {
        var label = labelMap[packageName]
        if (label != null) return label
        val info = installedApps[packageName] ?: return packageName
        label = info.loadLabel(app.packageManager).toString()
        labelMap[packageName] = label
        return label
    }

}
