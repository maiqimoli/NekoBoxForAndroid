package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.socks.parsePlainSOCKS
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class SocksSettingsActivity : ProfileSettingsActivity<SOCKSBean>() {
    override fun createEntity() = SOCKSBean()

    override fun SOCKSBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort

        DataStore.serverProtocolInt = protocol
        DataStore.serverUsername = username
        DataStore.serverPassword = password

        DataStore.profileCacheStore.putBoolean("sUoT", sUoT)
    }

    override fun SOCKSBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort

        protocol = DataStore.serverProtocolInt
        username = DataStore.serverUsername
        password = DataStore.serverPassword

        sUoT = DataStore.profileCacheStore.getBoolean("sUoT")
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.socks_preferences)
        val profileName = findPreference<EditTextPreference>(Key.PROFILE_NAME)!!
        val address = findPreference<EditTextPreference>(Key.SERVER_ADDRESS)!!
        val port = findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        val username = findPreference<EditTextPreference>(Key.SERVER_USERNAME)!!
        val password = findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        val protocol = findPreference<SimpleMenuPreference>(Key.SERVER_PROTOCOL)!!

        fun updateProtocol(version: Int) {
            password.isVisible = version == SOCKSBean.PROTOCOL_SOCKS5
        }

        updateProtocol(DataStore.serverProtocolInt)
        protocol.setOnPreferenceChangeListener { _, newValue ->
            updateProtocol((newValue as String).toInt())
            true
        }
        address.setOnPreferenceChangeListener { _, newValue ->
            val socks = parsePlainSOCKS(newValue as? String ?: return@setOnPreferenceChangeListener true)
                ?: return@setOnPreferenceChangeListener true

            address.text = socks.serverAddress
            port.text = socks.serverPort.toString()
            username.text = socks.username
            password.text = socks.password
            if (profileName.text.isNullOrBlank() && socks.name.isNotBlank()) {
                profileName.text = socks.name
            }
            protocol.value = SOCKSBean.PROTOCOL_SOCKS5.toString()
            updateProtocol(SOCKSBean.PROTOCOL_SOCKS5)
            false
        }
    }
}
