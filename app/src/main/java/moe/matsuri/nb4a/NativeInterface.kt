package moe.matsuri.nb4a

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Build.VERSION_CODES
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.AssetImportPolicy
import io.nekohasekai.sagernet.utils.PackageCache
import libcore.BoxPlatformInterface
import libcore.Libcore
import libcore.NB4AInterface
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress

private data class SelectorLifecycleSnapshot(
    val proxy: ProxyInstance,
    val notification: ServiceNotification,
    val instanceToken: Long,
    val startGeneration: Long,
    val stoppingGeneration: Long,
    val selectorCallbackGeneration: Long,
)

private data class StagingFileIdentity(
    val device: Long,
    val inode: Long,
)

class NativeInterface : BoxPlatformInterface, NB4AInterface {

    //  libbox interface

    override fun autoDetectInterfaceControl(fd: Int) {
        DataStore.vpnService?.protect(fd)
    }

    override fun openTun(singTunOptionsJson: String, tunPlatformOptionsJson: String): Long {
        if (DataStore.vpnService == null) {
            throw Exception("no VpnService")
        }
        return DataStore.vpnService!!.startVpn(singTunOptionsJson, tunPlatformOptionsJson).toLong()
    }

    override fun useProcFS(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun findConnectionOwner(
        ipProto: Int, srcIp: String, srcPort: Int, destIp: String, destPort: Int
    ): Int {
        return SagerNet.connectivity.getConnectionOwnerUid(
            ipProto, InetSocketAddress(srcIp, srcPort), InetSocketAddress(destIp, destPort)
        )
    }

    override fun packageNameByUid(uid: Int): String {
        PackageCache.awaitLoadSync()

        if (uid <= 1000L) {
            return "android"
        }

        val packageNames = PackageCache.uidMap[uid]
        if (!packageNames.isNullOrEmpty()) for (packageName in packageNames) {
            return packageName
        }

        error("unknown uid $uid")
    }

    override fun uidByPackageName(packageName: String): Int {
        PackageCache.awaitLoadSync()
        return PackageCache[packageName] ?: 0
    }

    // TODO: 'getter for connectionInfo: WifiInfo!' is deprecated
    override fun wifiState(): String {
        val wifiManager =
            app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION") // WifiInfo 字段无替代
        val connectionInfo = wifiManager.connectionInfo
        return "${connectionInfo.ssid},${connectionInfo.bssid}"
    }

    // nb4a interface

    override fun useOfficialAssets(): Boolean {
        return DataStore.rulesProvider == 0
    }

    override fun publishBundledAsset(
        name: String,
        bundledVersion: String,
        stagedAssetPath: String,
    ) {
        if (name != "geoip.db" && name != "geosite.db") {
            throw IOException("Unsupported bundled asset $name")
        }

        val directory = SagerNet.application.externalAssets.absoluteFile
        val stagedAsset = File(stagedAssetPath).absoluteFile
        val expectedDirectChild = File(directory, stagedAsset.name).absoluteFile
        if (stagedAsset != expectedDirectChild || stagedAsset.parentFile != directory) {
            throw IOException("Bundled asset staging path is outside the asset directory")
        }

        val stagingIdentity = inspectRegularStagingFile(stagedAsset)
        try {
            AssetImportPolicy.publishBundledAsset(
                directory = directory,
                name = name,
                bundledVersion = bundledVersion,
                stagedAsset = stagedAsset,
                useOfficialAssets = { DataStore.rulesProvider == 0 },
                validateAsset = { candidate, destinationName ->
                    AssetImportPolicy.isRecognizedAsset(candidate, destinationName) { geoIp ->
                        Libcore.validateGeoIP(geoIp.absolutePath)
                        true
                    }
                },
                move = { source, target ->
                    Os.rename(source.absolutePath, target.absolutePath)
                },
            )
        } finally {
            removeOriginalStagingFile(stagedAsset, stagingIdentity)
        }
    }

    private fun inspectRegularStagingFile(file: File): StagingFileIdentity {
        val stat = try {
            Os.lstat(file.absolutePath)
        } catch (failure: ErrnoException) {
            throw IOException("Failed to inspect bundled asset staging file", failure)
        }
        if (!OsConstants.S_ISREG(stat.st_mode)) {
            throw IOException("Bundled asset staging path is not a regular file")
        }
        return StagingFileIdentity(stat.st_dev, stat.st_ino)
    }

    private fun removeOriginalStagingFile(file: File, expected: StagingFileIdentity) {
        try {
            val stat = Os.lstat(file.absolutePath)
            if (OsConstants.S_ISREG(stat.st_mode) &&
                stat.st_dev == expected.device && stat.st_ino == expected.inode
            ) {
                Os.remove(file.absolutePath)
            }
        } catch (failure: ErrnoException) {
            if (failure.errno != OsConstants.ENOENT) {
                Logs.w("Failed to remove bundled asset staging file", failure)
            }
        }
    }

    override fun selector_OnProxySelected(instanceToken: Long, selectorTag: String, tag: String) {
        if (instanceToken == 0L) return
        if (selectorTag != "proxy") {
            Logs.d("other selector: $selectorTag")
            return
        }
        val service = DataStore.baseService ?: return
        val data = service.data
        val snapshot = synchronized(data.stoppingLock) {
            if (DataStore.baseService !== service) return
            val proxy = data.proxy ?: return
            val notification = data.notification ?: return
            if (data.state != BaseService.State.Connected ||
                data.stoppingDeferred?.isActive == true ||
                data.hardStopRequested ||
                !proxy.isInitialized()
            ) return
            if (proxy.box.instanceToken() != instanceToken) return
            data.selectorCallbackGeneration += 1L
            SelectorLifecycleSnapshot(
                proxy = proxy,
                notification = notification,
                instanceToken = instanceToken,
                startGeneration = data.startGeneration,
                stoppingGeneration = data.stoppingGeneration,
                selectorCallbackGeneration = data.selectorCallbackGeneration,
            )
        }

        runOnDefaultDispatcher {
            fun lifecycleIsCurrentLocked(): Boolean =
                DataStore.baseService === service &&
                        data.proxy === snapshot.proxy &&
                        data.notification === snapshot.notification &&
                        data.state == BaseService.State.Connected &&
                        data.stoppingDeferred?.isActive != true &&
                        !data.hardStopRequested &&
                        snapshot.proxy.isInitialized() &&
                        snapshot.proxy.box.instanceToken() == snapshot.instanceToken &&
                        data.startGeneration == snapshot.startGeneration &&
                        data.stoppingGeneration == snapshot.stoppingGeneration &&
                        data.selectorCallbackGeneration == snapshot.selectorCallbackGeneration

            fun lifecycleIsCurrent(): Boolean = synchronized(data.stoppingLock) {
                lifecycleIsCurrentLocked()
            }

            if (!lifecycleIsCurrent()) return@runOnDefaultDispatcher
            val id = snapshot.proxy.config.profileTagMap
                .filterValues { it == tag }.keys.firstOrNull() ?: -1
            val ent = SagerDatabase.proxyDao.getById(id) ?: return@runOnDefaultDispatcher
            if (!lifecycleIsCurrent()) return@runOnDefaultDispatcher
            val groupName = if (DataStore.showGroupInNotification) {
                SagerDatabase.groupDao.getById(ent.groupId)?.displayName()
            } else {
                null
            }
            if (!lifecycleIsCurrent()) return@runOnDefaultDispatcher

            if (snapshot.proxy.looper?.selectMain(id, ::lifecycleIsCurrent) == false) {
                return@runOnDefaultDispatcher
            }
            if (!lifecycleIsCurrent()) return@runOnDefaultDispatcher
            val title = ServiceNotification.genTitle(ent, groupName)
            val titleCommitted = synchronized(data.stoppingLock) {
                if (!lifecycleIsCurrentLocked()) {
                    false
                } else {
                    snapshot.proxy.displayProfileName = title
                    snapshot.notification.postNotificationTitle(title)
                }
            }
            if (!titleCommitted) return@runOnDefaultDispatcher

            data.binder.broadcast { callback ->
                if (lifecycleIsCurrent()) callback.cbSelectorUpdate(id)
            }
        }
    }

}
