package moe.matsuri.nb4a

import android.content.Context
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_NEKO
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
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
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_HTTP -> getColour(R.color.cyber_protocol_blue)
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_SS -> getColour(R.color.cyber_protocol_green)
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_VMESS -> getColour(R.color.cyber_protocol_cyan)
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_TROJAN,
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_TROJAN_GO -> getColour(R.color.cyber_protocol_orange)
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_HYSTERIA,
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_TUIC,
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_MIERU -> getColour(R.color.cyber_protocol_coral)
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_CHAIN -> getColour(R.color.cyber_protocol_purple)
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_WG,
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_SSH,
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_SHADOWTLS,
            io.nekohasekai.sagernet.database.ProxyEntity.TYPE_ANYTLS -> getColour(R.color.cyber_protocol_teal)
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
