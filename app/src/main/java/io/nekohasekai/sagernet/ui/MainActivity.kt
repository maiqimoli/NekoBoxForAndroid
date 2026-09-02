package io.nekohasekai.sagernet.ui

import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.view.KeyEvent
import android.view.MenuItem
import androidx.activity.addCallback
import androidx.annotation.IdRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.group.GroupInterfaceAdapter
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.isPlay
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.tryToShow
import io.nekohasekai.sagernet.utils.PackageCache
import moe.matsuri.nb4a.plugin.Plugins
import moe.matsuri.nb4a.utils.Util
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener,
    NavigationView.OnNavigationItemSelectedListener {

    lateinit var binding: LayoutMainBinding
    lateinit var navigation: NavigationView
    private var reconnectAfterPluginTrust = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reconnectAfterPluginTrust = savedInstanceState?.getBoolean(
            STATE_RECONNECT_AFTER_PLUGIN_TRUST
        ) == true

        binding = LayoutMainBinding.inflate(layoutInflater)
        binding.fab.initProgress(binding.fabProgress)
        if (themeResId !in intArrayOf(
                R.style.Theme_SagerNet_Black
            )
        ) {
            navigation = binding.navView
            binding.drawerLayout.removeView(binding.navViewBlack)
        } else {
            navigation = binding.navViewBlack
            binding.drawerLayout.removeView(binding.navView)
        }
        navigation.setNavigationItemSelectedListener(this)

        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_configuration)
        }
        onBackPressedDispatcher.addCallback {
            if (supportFragmentManager.findFragmentById(R.id.fragment_holder) is ConfigurationFragment) {
                moveTaskToBack(true)
            } else {
                displayFragmentWithId(R.id.nav_configuration)
            }
        }

        binding.fab.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            if (DataStore.serviceState.canStop) {
                SagerNet.stopService()
            } else {
                checkProfileBeforeConnect()
            }
        }
        binding.stats.setOnClickListener {
            if (DataStore.serviceState.connected) {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                binding.stats.testConnection()
            }
        }

        setContentView(binding.root)
        changeState(BaseService.State.Idle)
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)
        GroupManager.userInterface = GroupInterfaceAdapter(this)

        if (intent?.action == Intent.ACTION_VIEW) {
            onNewIntent(intent)
        }

        refreshNavMenu(DataStore.enableClashAPI)

        // sdk 33 notification
        if (Build.VERSION.SDK_INT >= 33) {
            val checkPermission =
                ContextCompat.checkSelfPermission(this@MainActivity, POST_NOTIFICATIONS)
            if (checkPermission != PackageManager.PERMISSION_GRANTED) {
                //动态申请
                ActivityCompat.requestPermissions(
                    this@MainActivity, arrayOf(POST_NOTIFICATIONS), 0
                )
            }
        }

    }

    fun refreshNavMenu(clashApi: Boolean) {
        if (::navigation.isInitialized) {
            navigation.menu.findItem(R.id.nav_traffic)?.isVisible = clashApi
            navigation.menu.findItem(R.id.nav_tuiguang)?.isVisible = !isPlay
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val uri = intent.data ?: return

        runOnDefaultDispatcher {
            runImportSafely {
                if (SubscriptionImportHelper.shouldImportSubscription(uri.scheme, uri.host)) {
                    importSubscriptionInternal(uri)
                } else {
                    importProfileInternal(uri)
                }
            }
        }
    }

    fun urlTest(): Int {
        if (!DataStore.serviceState.connected || connection.service == null) {
            error("not started")
        }
        return connection.service!!.urlTest()
    }

    suspend fun importSubscription(uri: Uri) = runImportSafely {
        importSubscriptionInternal(uri)
    }

    private suspend fun importSubscriptionInternal(uri: Uri) {
        val group = SubscriptionImportHelper.createDirectSubscriptionGroup(
            uri.scheme,
            uri.host,
            uri.encodedPath,
            uri.encodedQuery,
            uri.encodedFragment,
            uri::getQueryParameter,
        ) ?: run {
            val data = uri.encodedQuery.takeIf { !it.isNullOrBlank() }
                ?: error(getString(R.string.no_proxies_found_in_subscription))
            KryoConverters.deserialize(
                ProxyGroup().apply { export = true }, Util.zlibDecompress(Util.b64Decode(data))
            ).apply {
                export = false
            }
        }

        val name = SubscriptionImportHelper.fallbackGroupName(group, System.currentTimeMillis())
        group.name = name

        onMainDispatcher {

            displayFragmentWithId(R.id.nav_group)

            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.subscription_import)
                .setMessage(getString(R.string.subscription_import_message, name))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportSubscription(group)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

        }

    }
    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        GroupManager.createGroup(subscription)
        GroupUpdater.startUpdate(subscription, true)
    }

    suspend fun importProfile(uri: Uri) = runImportSafely {
        importProfileInternal(uri)
    }

    private suspend fun importProfileInternal(uri: Uri) {
        val profile =
            parseProxies(uri.toString()).getOrNull(0) ?: error(getString(R.string.no_proxies_found))

        onMainDispatcher {
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportProfile(profile)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

    }

    private suspend fun runImportSafely(importBlock: suspend () -> Unit) {
        try {
            importBlock()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onMainDispatcher {
                alert(error.readableMessage).tryToShow()
            }
        }
    }

    private suspend fun finishImportProfile(profile: AbstractBean) {
        val targetId = DataStore.selectedGroupForImport()

        ProfileManager.createProfile(targetId, profile)

        onMainDispatcher {
            displayFragmentWithId(R.id.nav_configuration)

            snackbar(resources.getQuantityString(R.plurals.added, 1, 1)).show()
        }
    }

    override fun missingPlugin(profileName: String, pluginName: String) {
        lifecycleScope.launch {
            val trustCandidates = withContext(Dispatchers.IO) {
                runCatching {
                    Plugins.getRejectedPluginPackages(pluginName)
                        .mapNotNull(PackageCache::getPluginTrustCandidate)
                }.getOrDefault(emptyList())
            }
            if (trustCandidates.isNotEmpty()) {
                showPluginTrustCandidates(trustCandidates)
            } else {
                showMissingPluginDialog(profileName, pluginName)
            }
        }
    }

    private fun showPluginTrustCandidates(
        trustCandidates: List<PackageCache.PluginTrustCandidate>,
    ) {
        if (trustCandidates.size == 1) {
            showPluginTrustConfirmation(trustCandidates.single())
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.plugin_trust_choose_title)
                .setItems(
                    trustCandidates.map { "${it.label} (${it.packageName})" }.toTypedArray()
                ) { _, which ->
                    showPluginTrustConfirmation(trustCandidates[which])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showMissingPluginDialog(profileName: String, pluginName: String) {
        val pluginEntity = PluginEntry.find(pluginName)

        // unknown exe or neko plugin
        if (pluginEntity == null) {
            snackbar(getString(R.string.plugin_unknown, pluginName)).show()
            return
        }

        // official exe

        MaterialAlertDialogBuilder(this).setTitle(R.string.missing_plugin)
            .setMessage(
                getString(
                    R.string.profile_requiring_plugin, profileName, pluginEntity.displayName
                )
            )
            .setPositiveButton(R.string.action_download) { _, _ ->
                showDownloadDialog(pluginEntity)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.action_learn_more) { _, _ ->
                launchCustomTab("https://matsuridayo.github.io/nb4a-plugin/")
            }
            .show()
    }

    private fun showPluginTrustConfirmation(candidate: PackageCache.PluginTrustCandidate) {
        val fingerprints = candidate.signerDigests.sorted().joinToString("\n") { digest ->
            digest.chunked(2).joinToString(":").uppercase()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.plugin_trust_title)
            .setMessage(
                getString(
                    R.string.plugin_trust_message,
                    candidate.label,
                    candidate.packageName,
                    fingerprints,
                )
            )
            .setPositiveButton(R.string.plugin_trust_action) { _, _ ->
                lifecycleScope.launch {
                    val trusted = withContext(Dispatchers.IO) {
                        PackageCache.trustPluginPackage(
                            candidate.packageName,
                            candidate.signerDigests,
                        )
                    }
                    if (trusted) {
                        reconnectAfterPluginTrust = true
                        reconnectAfterPluginTrustIfStopped(DataStore.serviceState)
                    } else {
                        snackbar(R.string.plugin_trust_signature_changed).show()
                        val refreshed = withContext(Dispatchers.IO) {
                            PackageCache.getPluginTrustCandidate(candidate.packageName)
                        }
                        if (refreshed != null) {
                            showPluginTrustConfirmation(refreshed)
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun reconnectAfterPluginTrustIfStopped(state: BaseService.State) {
        if (reconnectAfterPluginTrust && state == BaseService.State.Stopped) {
            reconnectAfterPluginTrust = false
            connect.launch(null)
        }
    }

    private fun showDownloadDialog(pluginEntry: PluginEntry) {
        var index = 0
        var playIndex = -1
        var fdroidIndex = -1

        val items = mutableListOf<String>()
        if (pluginEntry.downloadSource.playStore) {
            items.add(getString(R.string.install_from_play_store))
            playIndex = index++
        }
        if (pluginEntry.downloadSource.fdroid) {
            items.add(getString(R.string.install_from_fdroid))
            fdroidIndex = index++
        }

        items.add(getString(R.string.download))
        val downloadIndex = index

        MaterialAlertDialogBuilder(this).setTitle(pluginEntry.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    playIndex -> launchCustomTab("https://play.google.com/store/apps/details?id=${pluginEntry.packageName}")
                    fdroidIndex -> launchCustomTab("https://f-droid.org/packages/${pluginEntry.packageName}/")
                    downloadIndex -> launchCustomTab(pluginEntry.downloadSource.downloadLink)
                }
            }
            .show()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (item.isChecked) binding.drawerLayout.closeDrawers() else {
            return displayFragmentWithId(item.itemId)
        }
        return true
    }


    @SuppressLint("CommitTransaction")
    fun displayFragment(fragment: ToolbarFragment) {
        if (fragment is ConfigurationFragment) {
            binding.stats.showForPrimaryScreen()
            binding.fab.show()
        } else if (!DataStore.showBottomBar) {
            binding.stats.allowShow = false
            binding.stats.performHide()
            binding.fab.hide()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .commitAllowingStateLoss()
        binding.drawerLayout.closeDrawers()
    }

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        when (id) {
            R.id.nav_configuration -> {
                displayFragment(ConfigurationFragment())
            }

            R.id.nav_group -> displayFragment(GroupFragment())
            R.id.nav_route -> displayFragment(RouteFragment())
            R.id.nav_settings -> displayFragment(SettingsFragment())
            R.id.nav_traffic -> displayFragment(WebviewFragment())
            R.id.nav_tools -> displayFragment(ToolsFragment())
            R.id.nav_logcat -> displayFragment(LogcatFragment())
            R.id.nav_faq -> {
                launchCustomTab("https://matsuridayo.github.io/")
                return false
            }

            R.id.nav_about -> displayFragment(AboutFragment())
            R.id.nav_tuiguang -> {
                launchCustomTab("https://neko-box.pages.dev/喵")
                return false
            }

            else -> return false
        }
        navigation.menu.findItem(id).isChecked = true
        return true
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
    ) {
        val previousState = DataStore.serviceState
        DataStore.serviceState = state

        if (Build.VERSION.SDK_INT >= 30) {
            when (state) {
                BaseService.State.Connected ->
                    binding.fab.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                BaseService.State.Stopped ->
                    binding.fab.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT)
                else -> Unit
            }
        }

        binding.fab.changeState(state, previousState, animate)
        binding.stats.changeState(state, msg)
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            if (binding.fab.isShown) {
                anchorView = binding.fab
            }
            // TODO
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg, true)
        reconnectAfterPluginTrustIfStopped(state)
    }

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    override fun onServiceConnected(service: ISagerNetService) {
        val state = try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
        changeState(state)
        reconnectAfterPluginTrustIfStopped(state)
    }

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    private fun checkProfileBeforeConnect() {
        runOnDefaultDispatcher {
            val selectedProfile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
            val hasAnyProfile = SagerDatabase.proxyDao.getAll().isNotEmpty()
            onMainDispatcher {
                when {
                    selectedProfile != null -> checkRuleAssetsBeforeConnect { connect.launch(null) }

                    hasAnyProfile -> {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(R.string.profile_empty)
                            .setMessage(R.string.profile_empty_select_hint)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }

                    else -> {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(R.string.profile_empty)
                            .setMessage(R.string.profile_empty_add_hint)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
        }
    }

    // may NOT called when app is in background
    // ONLY do UI update here, write DB in bg process
    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        binding.stats.updateSpeed(stats.txRateProxy, stats.rxRateProxy)
    }

    override fun cbTrafficUpdate(data: TrafficData) {
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(data)
        }
    }

    override fun cbSelectorUpdate(id: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        ProfileUiState.markRecent(id)
        (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment)
            ?.refreshRecentUi()
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) =
        handlePreferenceStoreChange(key)

    override fun onStart() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
    }

    override fun onStop() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_RECONNECT_AFTER_PLUGIN_TRUST, reconnectAfterPluginTrust)
        super.onSaveInstanceState(outState)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleKeyDown(keyCode, event) { super.onKeyDown(keyCode, event) }

    private companion object {
        const val STATE_RECONNECT_AFTER_PLUGIN_TRUST = "reconnect_after_plugin_trust"
    }

}
