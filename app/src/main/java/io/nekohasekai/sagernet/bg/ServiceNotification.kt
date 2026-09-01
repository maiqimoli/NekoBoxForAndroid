package io.nekohasekai.sagernet.bg

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ui.SwitchActivity
import io.nekohasekai.sagernet.utils.Theme

/**
 * User can customize visibility of notification since Android 8.
 * The default visibility:
 *
 * Android 8.x: always visible due to system limitations
 * VPN:         always invisible because of VPN notification/icon
 * Other:       always visible
 *
 * See also: https://github.com/aosp-mirror/platform_frameworks_base/commit/070d142993403cc2c42eca808ff3fafcee220ac4
 */
class ServiceNotification(
    private val service: BaseService.Interface, title: String,
    channel: String, visible: Boolean = false,
) : BroadcastReceiver() {
    companion object {
        const val notificationId = 1
        val flags = PendingIntent.FLAG_IMMUTABLE

        fun genTitle(ent: ProxyEntity, groupName: String?): String =
            if (groupName == null) ent.displayName() else "[$groupName] ${ent.displayName()}"
    }

    @Volatile
    var listenPostSpeed = SagerNet.power.isInteractive

    fun postNotificationSpeedUpdate(stats: SpeedDisplayData): Boolean =
        synchronized(lifecycleLock) {
            if (destroyed) return@synchronized false
            if (showDirectSpeed) {
                val speedDetail = (service as Context).getString(
                    R.string.speed_detail, service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.txRateProxy)
                    ), service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.rxRateProxy)
                    ), service.getString(
                        R.string.speed,
                        Formatter.formatFileSize(service, stats.txRateDirect)
                    ), service.getString(
                        R.string.speed,
                        Formatter.formatFileSize(service, stats.rxRateDirect)
                    )
                )
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(speedDetail))
                builder.setContentText(speedDetail)
            } else {
                val speedSimple = (service as Context).getString(
                    R.string.traffic, service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.txRateProxy)
                    ), service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.rxRateProxy)
                    )
                )
                builder.setContentText(speedSimple)
            }
            builder.setSubText(
                service.getString(
                    R.string.traffic,
                    Formatter.formatFileSize(service, stats.txTotal),
                    Formatter.formatFileSize(service, stats.rxTotal)
                )
            )
            updateLocked()
            true
        }

    fun postNotificationTitle(newTitle: String): Boolean = synchronized(lifecycleLock) {
        if (destroyed) return@synchronized false
        builder.setContentTitle(newTitle)
        updateLocked()
        true
    }

    fun postNotificationWakeLockStatus(acquired: Boolean): Boolean =
        synchronized(lifecycleLock) {
            if (destroyed) return@synchronized false
            updateActionsLocked()
            builder.priority =
                if (acquired) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW
            updateLocked()
            true
        }

    private val showDirectSpeed = DataStore.showDirectSpeed

    private val builder = NotificationCompat.Builder(service as Context, channel)
        .setWhen(0)
        .setTicker(service.getString(R.string.forward_success))
        .setContentTitle(title)
        .setOnlyAlertOnce(true)
        .setContentIntent(SagerNet.configureIntent(service))
        .setSmallIcon(R.drawable.ic_service_active)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(if (visible) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)

    private val lifecycleLock = Any()
    private var destroyed = false
    private var receiverRegistered = false

    init {
        service as Context

        Theme.apply(app)
        Theme.apply(service)
        builder.color = service.getColorAttr(R.attr.colorPrimary)

        synchronized(lifecycleLock) {
            service.registerReceiver(this, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            })
            receiverRegistered = true
        }

    }

    private fun updateActionsLocked() {
        service as Context
        builder.clearActions()

        val closeAction = NotificationCompat.Action.Builder(
            0, service.getText(R.string.stop), PendingIntent.getBroadcast(
                service, 0, Intent(Action.CLOSE).setPackage(service.packageName), flags
            )
        ).setShowsUserInterface(false).build()
        builder.addAction(closeAction)

        val switchAction = NotificationCompat.Action.Builder(
            0, service.getString(R.string.action_switch), PendingIntent.getActivity(
                service, 0, Intent(service, SwitchActivity::class.java), flags
            )
        ).setShowsUserInterface(false).build()
        builder.addAction(switchAction)

        val resetUpstreamAction = NotificationCompat.Action.Builder(
            0, service.getString(R.string.reset_connections),
            PendingIntent.getBroadcast(
                service, 0, Intent(Action.RESET_UPSTREAM_CONNECTIONS), flags
            )
        ).setShowsUserInterface(false).build()
        builder.addAction(resetUpstreamAction)
    }

    override fun onReceive(context: Context, intent: Intent) {
        synchronized(lifecycleLock) {
            if (!destroyed && service.data.state == BaseService.State.Connected) {
                listenPostSpeed = intent.action == Intent.ACTION_SCREEN_ON
            }
        }
    }

    fun show(): Boolean = synchronized(lifecycleLock) {
        if (destroyed) return@synchronized false
        updateActionsLocked()
        if (Build.VERSION.SDK_INT >= 34) {
            (service as Service).startForeground(
                notificationId,
                builder.build(),
                FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            (service as Service).startForeground(notificationId, builder.build())
        }
        true
    }

    private fun updateLocked() {
        val s = service as Service
        if (Build.VERSION.SDK_INT < 33 ||
            s.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(s).notify(notificationId, builder.build())
        }
    }

    fun destroy() {
        var failure: Throwable? = null
        synchronized(lifecycleLock) {
            if (destroyed) return
            destroyed = true
            listenPostSpeed = false
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    (service as Service).stopForeground(Service.STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION") // API < 24 只能使用旧重载
                    (service as Service).stopForeground(true)
                }
            } catch (error: Throwable) {
                failure = error
            }
            if (receiverRegistered) {
                receiverRegistered = false
                try {
                    (service as Context).unregisterReceiver(this)
                } catch (error: Throwable) {
                    failure?.let { current ->
                        if (current !== error) current.addSuppressed(error)
                    } ?: run { failure = error }
                }
            }
        }
        failure?.let { throw it }
    }
}
