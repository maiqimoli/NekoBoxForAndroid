package moe.matsuri.nb4a

import android.content.Context
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_NEKO
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr
import moe.matsuri.nb4a.proxy.config.ConfigBean

// Settings for all protocols, built-in or plugin
object Protocols {

    // Deduplication

    class Deduplication(
        val bean: AbstractBean, val type: String
    ) {

        fun hash(): String {
            if (bean is ConfigBean) {
                return bean.config
            }
            return bean.serverAddress + bean.serverPort + type
        }

        override fun hashCode(): Int {
            return hash().toByteArray().contentHashCode()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Deduplication

            return hash() == other.hash()
        }

    }

    // Display

    fun Context.getProtocolColor(type: Int): Int {
        return when (type) {
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_SOCKS,
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_HTTP -> android.graphics.Color.parseColor("#42A5F5")
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_SS -> android.graphics.Color.parseColor("#66BB6A")
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_VMESS -> android.graphics.Color.parseColor("#26C6DA")
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_TROJAN,
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_TROJAN_GO -> android.graphics.Color.parseColor("#FFA726")
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_HYSTERIA,
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_TUIC,
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_MIERU -> android.graphics.Color.parseColor("#FF7043")
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_CHAIN -> android.graphics.Color.parseColor("#AB47BC")
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_WG,
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_SSH,
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_SHADOWTLS,
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_ANYTLS -> android.graphics.Color.parseColor("#26A69A")
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_NEKO -> getColorAttr(android.R.attr.textColorPrimary)
            else -> getColorAttr(R.attr.accentOrTextSecondary)
        }
    }

    // Test

    fun genFriendlyMsg(msg: String): String {
        val msgL = msg.lowercase()
        return when {
            msgL.contains("timeout") || msgL.contains("deadline") -> {
                app.getString(R.string.connection_test_timeout)
            }

            msgL.contains("refused") || msgL.contains("closed pipe") -> {
                app.getString(R.string.connection_test_refused)
            }

            else -> msg
        }
    }

}