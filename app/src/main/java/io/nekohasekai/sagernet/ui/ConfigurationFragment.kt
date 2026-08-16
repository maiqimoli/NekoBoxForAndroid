package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder

import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceDataStore
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutProfileListBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressListBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.closeQuietly
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnLifecycleDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ktx.scrollTo
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ktx.tryToShow
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.ui.profile.ChainSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HttpSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HysteriaSettingsActivity
import io.nekohasekai.sagernet.ui.profile.MieruSettingsActivity
import io.nekohasekai.sagernet.ui.profile.NaiveSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SSHSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanGoSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TuicSettingsActivity
import io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity
import io.nekohasekai.sagernet.ui.profile.WireGuardSettingsActivity
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import moe.matsuri.nb4a.proxy.config.ConfigSettingActivity
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSSettingsActivity
import moe.matsuri.nb4a.ui.ConnectionTestNotification
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

class ConfigurationFragment @JvmOverloads constructor(
    val select: Boolean = false, val selectedItem: ProxyEntity? = null, val titleRes: Int = 0
) : ToolbarFragment(R.layout.layout_group_list),
    PopupMenu.OnMenuItemClickListener,
    Toolbar.OnMenuItemClickListener,
    SearchView.OnQueryTextListener,
    OnPreferenceDataStoreChangeListener {

    interface SelectCallback {
        fun returnProfile(profileId: Long)
    }

    lateinit var adapter: GroupPagerAdapter
    lateinit var tabLayout: TabLayout
    lateinit var groupPager: ViewPager2

    val alwaysShowAddress by lazy { DataStore.alwaysShowAddress }
    val expandedChainIds = ConcurrentHashMap.newKeySet<Long>()
    val chainHopDetails = ConcurrentHashMap<Long, String>()
    val chainHopTesting = ConcurrentHashMap.newKeySet<Long>()

    fun getCurrentProfileGroupFragment(): ProfileGroupFragment? {
        return try {
            childFragmentManager.findFragmentByTag("f" + DataStore.selectedGroup) as ProfileGroupFragment?
        } catch (e: Exception) {
            Logs.e(e)
            null
        }
    }

    private fun refreshChainCard(chainId: Long) {
        runOnMainDispatcher {
            val adapter = getCurrentProfileGroupFragment()?.adapter ?: return@runOnMainDispatcher
            val index = adapter.configurationIdList.indexOf(chainId)
            if (index >= 0) adapter.notifyItemChanged(index)
        }
    }

    fun testChainHops(proxyEntity: ProxyEntity) {
        if (proxyEntity.type != ProxyEntity.TYPE_CHAIN) return
        if (!chainHopTesting.add(proxyEntity.id)) return
        chainHopDetails[proxyEntity.id] = getString(R.string.connection_test_testing)
        refreshChainCard(proxyEntity.id)

        val context = requireContext().applicationContext
        runOnDefaultDispatcher {
            try {
                ChainHopTester.test(context, proxyEntity) {
                    chainHopDetails[proxyEntity.id] = it
                    refreshChainCard(proxyEntity.id)
                }
            } finally {
                chainHopTesting.remove(proxyEntity.id)
                refreshChainCard(proxyEntity.id)
            }
        }
    }

    val updateSelectedCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrolled(
            position: Int, positionOffset: Float, positionOffsetPixels: Int
        ) {
            if (adapter.groupList.size > position) {
                DataStore.selectedGroup = adapter.groupList[position].id
            }
        }
    }

    override fun onQueryTextChange(query: String): Boolean {
        getCurrentProfileGroupFragment()?.adapter?.filter(query)
        return false
    }

    override fun onQueryTextSubmit(query: String): Boolean = false

    @SuppressLint("DetachAndAttachSameFragment")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            parentFragmentManager.beginTransaction()
                .setReorderingAllowed(false)
                .detach(this)
                .attach(this)
                .commit()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!select) {
            toolbar.inflateMenu(R.menu.add_profile_menu)
            toolbar.setOnMenuItemClickListener(this)
        } else {
            toolbar.setTitle(titleRes)
            toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
            toolbar.setNavigationOnClickListener {
                requireActivity().finish()
            }
        }

        val searchView = toolbar.findViewById<SearchView>(R.id.action_search)
        if (searchView != null) {
            searchView.setOnQueryTextListener(this)
            searchView.maxWidth = Int.MAX_VALUE

            searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    cancelSearch(searchView)
                }
            }
        }

        groupPager = view.findViewById(R.id.group_pager)
        tabLayout = view.findViewById(R.id.group_tab)
        adapter = GroupPagerAdapter()
        ProfileManager.addListener(adapter)
        GroupManager.addListener(adapter)

        groupPager.adapter = adapter
        groupPager.offscreenPageLimit = 2

        TabLayoutMediator(tabLayout, groupPager) { tab, position ->
            if (adapter.groupList.size > position) {
                tab.text = adapter.groupList[position].displayName()
            }
            tab.view.setOnLongClickListener { // clear toast
                true
            }
        }.attach()

        toolbar.setOnClickListener {
            val fragment = getCurrentProfileGroupFragment()

            if (fragment != null) {
                val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                val selectedProfileIndex =
                    fragment.adapter!!.configurationIdList.indexOf(selectedProxy)
                if (selectedProfileIndex != -1) {
                    val layoutManager = fragment.layoutManager
                    val first = layoutManager.findFirstVisibleItemPosition()
                    val last = layoutManager.findLastVisibleItemPosition()

                    if (selectedProfileIndex !in first..last) {
                        fragment.configurationListView.scrollTo(selectedProfileIndex, true)
                        return@setOnClickListener
                    }

                }

                fragment.configurationListView.scrollTo(0)
            }

        }

        DataStore.profileCacheStore.registerChangeListener(this)
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        runOnMainDispatcher {
            // editingGroup
            if (key == Key.PROFILE_GROUP) {
                val targetId = DataStore.editingGroup
                if (targetId > 0 && targetId != DataStore.selectedGroup) {
                    DataStore.selectedGroup = targetId
                    val targetIndex = adapter.groupList.indexOfFirst { it.id == targetId }
                    if (targetIndex >= 0) {
                        groupPager.setCurrentItem(targetIndex, false)
                    } else {
                        adapter.reload()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)

        if (::adapter.isInitialized) {
            GroupManager.removeListener(adapter)
            ProfileManager.removeListener(adapter)
        }

        super.onDestroy()
    }

    override fun onKeyDown(ketCode: Int, event: KeyEvent): Boolean {
        val fragment = getCurrentProfileGroupFragment()
        fragment?.configurationListView?.apply {
            if (!hasFocus()) requestFocus()
        }
        return super.onKeyDown(ketCode, event)
    }

    private val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
            if (file != null) runOnDefaultDispatcher {
                try {
                    val fileName =
                        requireContext().contentResolver.query(file, null, null, null, null)
                            ?.use { cursor ->
                                cursor.moveToFirst()
                                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                                    .let(cursor::getString)
                            }
                    val proxies = mutableListOf<AbstractBean>()
                    if (fileName != null && fileName.endsWith(".zip")) {
                        // try parse wireguard zip
                        val zip =
                            ZipInputStream(requireContext().contentResolver.openInputStream(file)!!)
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (entry.isDirectory) continue
                            val fileText = zip.bufferedReader().readText()
                            RawUpdater.parseRaw(fileText, entry.name)
                                ?.let { pl -> proxies.addAll(pl) }
                            zip.closeEntry()
                        }
                        zip.closeQuietly()
                    } else {
                        val fileText =
                            requireContext().contentResolver.openInputStream(file)!!.use {
                                it.bufferedReader().readText()
                            }
                        RawUpdater.parseRaw(fileText, fileName ?: "")
                            ?.let { pl -> proxies.addAll(pl) }
                    }
                    if (proxies.isEmpty()) onMainDispatcher {
                        snackbar(getString(R.string.no_proxies_found_in_file)).show()
                    } else import(proxies)
                } catch (e: SubscriptionFoundException) {
                    (requireActivity() as MainActivity).importSubscription(e.link.toUri())
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        snackbar(e.readableMessage).show()
                    }
                }
            }
        }

    suspend fun import(proxies: List<AbstractBean>) {
        val targetId = DataStore.selectedGroupForImport()
        for (proxy in proxies) {
            ProfileManager.createProfile(targetId, proxy)
        }
        onMainDispatcher {
            DataStore.editingGroup = targetId
            snackbar(
                requireContext().resources.getQuantityString(
                    R.plurals.added, proxies.size, proxies.size
                )
            ).show()
        }

    }

    fun showAddMenu() {
        val items = arrayOf(
            getString(R.string.add_profile_methods_scan_qr_code),
            getString(R.string.action_import),
            getString(R.string.action_import_file),
            getString(R.string.add_profile_methods_manual_settings)
        )
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.add_profile)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(context, ScannerActivity::class.java))
                    1 -> handleMenuAction(R.id.action_import_clipboard)
                    2 -> startFilesForResult(importFile, "*/*")
                    3 -> showManualAddMenu()
                }
            }
            .show()
    }

    private fun showManualAddMenu() {
        val items = arrayOf(
            getString(R.string.action_socks),
            getString(R.string.action_http),
            getString(R.string.action_shadowsocks),
            getString(R.string.action_vmess),
            "VLESS",
            getString(R.string.action_trojan),
            getString(R.string.action_trojan_go),
            getString(R.string.action_mieru),
            getString(R.string.action_naive),
            getString(R.string.action_hysteria),
            getString(R.string.action_tuic),
            getString(R.string.action_shadowtls),
            getString(R.string.action_anytls),
            getString(R.string.action_ssh),
            getString(R.string.action_wireguard),
            getString(R.string.custom_config),
            getString(R.string.proxy_chain)
        )
        val ids = intArrayOf(
            R.id.action_new_socks, R.id.action_new_http, R.id.action_new_ss, R.id.action_new_vmess,
            R.id.action_new_vless, R.id.action_new_trojan, R.id.action_new_trojan_go,
            R.id.action_new_mieru, R.id.action_new_naive, R.id.action_new_hysteria,
            R.id.action_new_tuic, R.id.action_new_shadowtls, R.id.action_new_anytls,
            R.id.action_new_ssh, R.id.action_new_wg, R.id.action_new_config, R.id.action_new_chain
        )
        MaterialAlertDialogBuilder(requireContext()).setTitle(
            R.string.add_profile_methods_manual_settings
        )
            .setItems(items) { _, which ->
                handleMenuAction(ids[which])
            }
            .show()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        return handleMenuAction(item.itemId)
    }

    private fun handleMenuAction(itemId: Int): Boolean {
        when (itemId) {
            R.id.action_settings -> {
                (requireActivity() as MainActivity).displayFragmentWithId(R.id.nav_settings)
                return true
            }

            R.id.action_scan_qr_code -> {
                startActivity(Intent(context, ScannerActivity::class.java))
            }

            R.id.action_import_clipboard -> {
                val text = SagerNet.getClipboardText()
                if (text.isBlank()) {
                    snackbar(getString(R.string.clipboard_empty)).show()
                } else runOnDefaultDispatcher {
                    try {
                        val proxies = RawUpdater.parseRaw(text)
                        if (proxies.isNullOrEmpty()) onMainDispatcher {
                            snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                        } else import(proxies)
                    } catch (e: SubscriptionFoundException) {
                        (requireActivity() as MainActivity).importSubscription(e.link.toUri())
                    } catch (e: Exception) {
                        Logs.w(e)

                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }
                }
            }

            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
            }

            R.id.action_new_socks -> {
                startActivity(Intent(requireActivity(), SocksSettingsActivity::class.java))
            }

            R.id.action_new_http -> {
                startActivity(Intent(requireActivity(), HttpSettingsActivity::class.java))
            }

            R.id.action_new_ss -> {
                startActivity(Intent(requireActivity(), ShadowsocksSettingsActivity::class.java))
            }

            R.id.action_new_vmess -> {
                startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java))
            }

            R.id.action_new_vless -> {
                startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java).apply {
                    putExtra("vless", true)
                })
            }

            R.id.action_new_trojan -> {
                startActivity(Intent(requireActivity(), TrojanSettingsActivity::class.java))
            }

            R.id.action_new_trojan_go -> {
                startActivity(Intent(requireActivity(), TrojanGoSettingsActivity::class.java))
            }

            R.id.action_new_mieru -> {
                startActivity(Intent(requireActivity(), MieruSettingsActivity::class.java))
            }

            R.id.action_new_naive -> {
                startActivity(Intent(requireActivity(), NaiveSettingsActivity::class.java))
            }

            R.id.action_new_hysteria -> {
                startActivity(Intent(requireActivity(), HysteriaSettingsActivity::class.java))
            }

            R.id.action_new_tuic -> {
                startActivity(Intent(requireActivity(), TuicSettingsActivity::class.java))
            }

            R.id.action_new_ssh -> {
                startActivity(Intent(requireActivity(), SSHSettingsActivity::class.java))
            }

            R.id.action_new_wg -> {
                startActivity(Intent(requireActivity(), WireGuardSettingsActivity::class.java))
            }

            R.id.action_new_shadowtls -> {
                startActivity(Intent(requireActivity(), ShadowTLSSettingsActivity::class.java))
            }

            R.id.action_new_anytls -> {
                startActivity(Intent(requireActivity(), AnyTLSSettingsActivity::class.java))
            }

            R.id.action_new_config -> {
                startActivity(Intent(requireActivity(), ConfigSettingActivity::class.java))
            }

            R.id.action_new_chain -> {
                startActivity(Intent(requireActivity(), ChainSettingsActivity::class.java))
            }

            R.id.action_update_subscription -> {
                val group = DataStore.currentGroup()
                if (group.type != GroupType.SUBSCRIPTION) {
                    snackbar(R.string.group_not_subscription).show()
                    Logs.e("onMenuItemClick: Group(${group.displayName()}) is not subscription")
                } else {
                    runOnLifecycleDispatcher {
                        GroupUpdater.startUpdate(group, true)
                    }
                }
            }

            R.id.action_clear_traffic_statistics -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.tx != 0L || profile.rx != 0L) {
                            profile.tx = 0
                            profile.rx = 0
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        ProfileManager.updateProfile(toClear)
                    }
                }
            }

            R.id.action_connection_test_clear_results -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.status != 0) {
                            profile.status = 0
                            profile.ping = 0
                            profile.error = null
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        ProfileManager.updateProfile(toClear)
                    }
                }
            }

            R.id.action_connection_test_delete_unavailable -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.status != 0 && profile.status != 1) {
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage(R.string.delete_confirm_prompt)
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    for (profile in toClear) {
                                        adapter.groupFragments[DataStore.selectedGroup]?.adapter?.apply {
                                            val index = configurationIdList.indexOf(profile.id)
                                            if (index >= 0) {
                                                configurationIdList.removeAt(index)
                                                configurationList.remove(profile.id)
                                                notifyItemRemoved(index)
                                            }
                                        }
                                    }
                                    runOnDefaultDispatcher {
                                        for (profile in toClear) {
                                            ProfileManager.deleteProfile2(
                                                profile.groupId, profile.id
                                            )
                                        }
                                    }
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        }
                    }
                }
            }

            R.id.action_remove_duplicate -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    val uniqueProxies = LinkedHashSet<Protocols.Deduplication>()
                    for (pf in profiles) {
                        val proxy = Protocols.Deduplication(pf.requireBean(), pf.displayType())
                        if (!uniqueProxies.add(proxy)) {
                            toClear += pf
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage(
                                    getString(R.string.delete_confirm_prompt) + "\n" +
                                            toClear.mapIndexedNotNull { index, proxyEntity ->
                                                if (index < 20) {
                                                    proxyEntity.displayName()
                                                } else if (index == 20) {
                                                    "......"
                                                } else {
                                                    null
                                                }
                                            }.joinToString("\n")
                                )
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    for (profile in toClear) {
                                        adapter.groupFragments[DataStore.selectedGroup]?.adapter?.apply {
                                            val index = configurationIdList.indexOf(profile.id)
                                            if (index >= 0) {
                                                configurationIdList.removeAt(index)
                                                configurationList.remove(profile.id)
                                                notifyItemRemoved(index)
                                            }
                                        }
                                    }
                                    runOnDefaultDispatcher {
                                        for (profile in toClear) {
                                            ProfileManager.deleteProfile2(
                                                profile.groupId, profile.id
                                            )
                                        }
                                    }
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        }
                    }
                }
            }

            R.id.action_connection_tcp_ping -> {
                pingTest(false)
            }

            R.id.action_connection_url_test -> {
                urlTest()
            }
        }
        return true
    }

    inner class TestDialog {
        val binding = LayoutProgressListBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext()).setView(binding.root)
            .setPositiveButton(R.string.minimize) { _, _ ->
                minimize()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                cancel()
            }
            .setCancelable(false)

        lateinit var cancel: () -> Unit
        lateinit var minimize: () -> Unit

        val dialogStatus = AtomicInteger(0) // 1: hidden 2: cancelled
        var notification: ConnectionTestNotification? = null

        val results: MutableSet<ProxyEntity> = ConcurrentHashMap.newKeySet()
        var proxyN = 0
        val finishedN = AtomicInteger(0)

        fun update(profile: ProxyEntity) {
            if (dialogStatus.get() != 2) {
                results.add(profile)
            }
            runOnMainDispatcher {
                val context = context ?: return@runOnMainDispatcher
                val progress = finishedN.addAndGet(1)
                val status = dialogStatus.get()
                notification?.updateNotification(
                    progress,
                    proxyN,
                    progress >= proxyN || status == 2
                )
                if (status >= 1) return@runOnMainDispatcher
                if (!isAdded) return@runOnMainDispatcher

                // refresh dialog

                var profileStatusText: String? = null
                var profileStatusColor = 0

                when (profile.status) {
                    -1 -> {
                        profileStatusText = profile.error
                        profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
                    }

                    0 -> {
                        profileStatusText = getString(R.string.connection_test_testing)
                        profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
                    }

                    1 -> {
                        profileStatusText = getString(R.string.available, profile.ping)
                        profileStatusColor = context.getColour(R.color.material_green_500)
                    }

                    2 -> {
                        profileStatusText = profile.error
                        profileStatusColor = context.getColour(R.color.material_red_500)
                    }

                    3 -> {
                        val err = profile.error ?: ""
                        val msg = Protocols.genFriendlyMsg(err)
                        profileStatusText = if (msg != err) msg else getString(R.string.unavailable)
                        profileStatusColor = context.getColour(R.color.material_red_500)
                    }
                }

                val text = SpannableStringBuilder().apply {
                    append("\n" + profile.displayName())
                    append("\n")
                    append(
                        profile.displayType(),
                        ForegroundColorSpan(context.getProtocolColor(profile.type)),
                        SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    append(" ")
                    append(
                        profileStatusText,
                        ForegroundColorSpan(profileStatusColor),
                        SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    append("\n")
                }

                binding.nowTesting.text = text
                binding.progress.text = "$progress / $proxyN"
            }
        }

    }

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("EXPERIMENTAL_API_USAGE")
    fun pingTest(icmpPing: Boolean) {
        if (DataStore.runningTest) return else DataStore.runningTest = true
        val test = TestDialog()
        val dialog = test.builder.show()
        val testJobs = mutableListOf<Job>()
        val group = DataStore.currentGroup()

        val mainJob = runOnDefaultDispatcher {
            val profilesList = SagerDatabase.proxyDao.getByGroup(group.id).filter {
                if (icmpPing) {
                    if (it.requireBean().canICMPing()) {
                        return@filter true
                    }
                } else {
                    if (it.requireBean().canTCPing()) {
                        return@filter true
                    }
                }
                return@filter false
            }
            test.proxyN = profilesList.size
            val profiles = ConcurrentLinkedQueue(profilesList)
            repeat(DataStore.connectionTestConcurrent) {
                testJobs.add(launch(Dispatchers.IO) {
                    while (isActive) {
                        val profile = profiles.poll() ?: break

                        profile.status = 0
                        var address = profile.requireBean().serverAddress
                        if (!address.isIpAddress()) {
                            try {
                                SagerNet.underlyingNetwork!!.getAllByName(address).apply {
                                    if (isNotEmpty()) {
                                        address = this[0].hostAddress
                                    }
                                }
                            } catch (e: UnknownHostException) {
                                Logs.w(e)
                            }
                        }
                        if (!isActive) break
                        if (!address.isIpAddress()) {
                            profile.status = 2
                            profile.error = app.getString(R.string.connection_test_domain_not_found)
                            test.update(profile)
                            continue
                        }
                        try {
                            if (icmpPing) {
                                // removed
                            } else {
                                val socket =
                                    SagerNet.underlyingNetwork?.socketFactory?.createSocket()
                                        ?: Socket()
                                try {
                                    socket.soTimeout = 3000
                                    socket.bind(InetSocketAddress(0))
                                    val start = SystemClock.elapsedRealtime()
                                    socket.connect(
                                        InetSocketAddress(
                                            address, profile.requireBean().serverPort
                                        ), 3000
                                    )
                                    if (!isActive) break
                                    profile.status = 1
                                    profile.ping = (SystemClock.elapsedRealtime() - start).toInt()
                                    test.update(profile)
                                } finally {
                                    socket.closeQuietly()
                                }
                            }
                        } catch (e: Exception) {
                            if (!isActive) break
                            val message = e.readableMessage

                            if (icmpPing) {
                                profile.status = 2
                                profile.error = getString(R.string.connection_test_unreachable)
                            } else {
                                profile.status = 2
                                when {
                                    !message.contains("failed:") -> profile.error =
                                        getString(R.string.connection_test_timeout)

                                    else -> when {
                                        message.contains("ECONNREFUSED") -> {
                                            profile.error =
                                                getString(R.string.connection_test_refused)
                                        }

                                        message.contains("ENETUNREACH") -> {
                                            profile.error =
                                                getString(R.string.connection_test_unreachable)
                                        }

                                        else -> {
                                            profile.status = 3
                                            profile.error = message
                                        }
                                    }
                                }
                            }
                            test.update(profile)
                        }
                    }
                })
            }

            testJobs.joinAll()

            runOnMainDispatcher {
                test.cancel()
            }
        }
        test.cancel = {
            test.dialogStatus.set(2)
            dialog.dismiss()
            runOnDefaultDispatcher {
                mainJob.cancel()
                testJobs.forEach { it.cancel() }
                test.results.forEach {
                    try {
                        ProfileManager.updateProfile(it)
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                }
                GroupManager.postReload(DataStore.currentGroupId())
                DataStore.runningTest = false
            }
        }
        test.minimize = {
            test.dialogStatus.set(1)
            test.notification = ConnectionTestNotification(
                dialog.context,
                "[${group.displayName()}] ${getString(R.string.connection_test)}"
            )
            dialog.hide()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun urlTest() {
        if (DataStore.runningTest) return else DataStore.runningTest = true
        val test = TestDialog()
        val dialog = test.builder.show()
        val testJobs = mutableListOf<Job>()
        val group = DataStore.currentGroup()

        val mainJob = runOnDefaultDispatcher {
            val profilesList = SagerDatabase.proxyDao.getByGroup(group.id)
            test.proxyN = profilesList.size
            val profiles = ConcurrentLinkedQueue(profilesList)
            repeat(DataStore.connectionTestConcurrent) {
                testJobs.add(launch(Dispatchers.IO) {
                    val urlTest = UrlTest() // note: this is NOT in bg process
                    while (isActive) {
                        val profile = profiles.poll() ?: break
                        profile.status = 0

                        try {
                            val result = urlTest.doTest(profile)
                            profile.status = 1
                            profile.ping = result
                        } catch (e: PluginManager.PluginNotFoundException) {
                            profile.status = 2
                            profile.error = e.readableMessage
                        } catch (e: Exception) {
                            profile.status = 3
                            profile.error = e.readableMessage
                        }

                        test.update(profile)
                    }
                })
            }

            testJobs.joinAll()

            runOnMainDispatcher {
                test.cancel()
            }
        }
        test.cancel = {
            test.dialogStatus.set(2)
            dialog.dismiss()
            runOnDefaultDispatcher {
                mainJob.cancel()
                testJobs.forEach { it.cancel() }
                test.results.forEach {
                    try {
                        ProfileManager.updateProfile(it)
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                }
                GroupManager.postReload(DataStore.currentGroupId())
                DataStore.runningTest = false
            }
        }
        test.minimize = {
            test.dialogStatus.set(1)
            test.notification = ConnectionTestNotification(
                dialog.context,
                "[${group.displayName()}] ${getString(R.string.connection_test)}"
            )
            dialog.hide()
        }
    }

    inner class GroupPagerAdapter : FragmentStateAdapter(this),
        ProfileManager.Listener,
        GroupManager.Listener {

        var selectedGroupIndex = 0
        var groupList: ArrayList<ProxyGroup> = ArrayList()
        var groupFragments: HashMap<Long, ProfileGroupFragment> = HashMap()

        fun reload(now: Boolean = false) {

            if (!select) {
                groupPager.unregisterOnPageChangeCallback(updateSelectedCallback)
            }

            runOnDefaultDispatcher {
                var newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                if (newGroupList.isEmpty()) {
                    SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
                    newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                }
                newGroupList.find { it.ungrouped }?.let {
                    if (SagerDatabase.proxyDao.countByGroup(it.id) == 0L) {
                        newGroupList.remove(it)
                    }
                }

                var selectedGroup = selectedItem?.groupId ?: DataStore.currentGroupId()
                var set = false
                if (selectedGroup > 0L) {
                    selectedGroupIndex = newGroupList.indexOfFirst { it.id == selectedGroup }
                    set = true
                } else if (groupList.size == 1) {
                    selectedGroup = groupList[0].id
                    if (DataStore.selectedGroup != selectedGroup) {
                        DataStore.selectedGroup = selectedGroup
                    }
                }

                val runFunc = if (now) activity?.let { it::runOnUiThread } else groupPager::post
                if (runFunc != null) {
                    runFunc {
                        groupList = newGroupList
                        notifyDataSetChanged()
                        if (set) groupPager.setCurrentItem(selectedGroupIndex, false)
                        val hideTab = groupList.size < 2
                        tabLayout.isGone = hideTab
                        toolbar.elevation = if (hideTab) 0F else dp2px(4).toFloat()
                        if (!select) {
                            groupPager.registerOnPageChangeCallback(updateSelectedCallback)
                        }
                    }
                }
            }
        }

        init {
            reload(true)
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun createFragment(position: Int): Fragment {
            return ProfileGroupFragment().apply {
                proxyGroup = groupList[position]
                groupFragments[proxyGroup.id] = this
                if (position == selectedGroupIndex) {
                    selected = true
                }
            }
        }

        override fun getItemId(position: Int): Long {
            return groupList[position].id
        }

        override fun containsItem(itemId: Long): Boolean {
            return groupList.any { it.id == itemId }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            tabLayout.post {
                groupList.add(group)

                if (groupList.any { !it.ungrouped }) tabLayout.post {
                    tabLayout.visibility = View.VISIBLE
                }

                notifyItemInserted(groupList.size - 1)
                tabLayout.getTabAt(groupList.size - 1)?.select()
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) return

            tabLayout.post {
                groupList.removeAt(index)
                notifyItemRemoved(index)
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            val index = groupList.indexOfFirst { it.id == group.id }
            if (index == -1) return

            tabLayout.post {
                tabLayout.getTabAt(index)?.text = group.displayName()
            }
        }

        override suspend fun groupUpdated(groupId: Long) = Unit

        override suspend fun onAdd(profile: ProxyEntity) {
            if (groupList.find { it.id == profile.groupId } == null) {
                DataStore.selectedGroup = profile.groupId
                reload()
            }
        }

        override suspend fun onUpdated(data: TrafficData) = Unit

        override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) = Unit

        override suspend fun onRemoved(groupId: Long, profileId: Long) {
            val group = groupList.find { it.id == groupId } ?: return
            if (group.ungrouped && SagerDatabase.proxyDao.countByGroup(groupId) == 0L) {
                reload()
            }
        }
    }

    val exportConfig =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    try {
                        (requireActivity() as MainActivity).contentResolver.openOutputStream(data)!!
                            .bufferedWriter()
                            .use {
                                it.write(DataStore.serverConfig)
                            }
                        onMainDispatcher {
                            snackbar(getString(R.string.action_export_msg)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }

                }
            }
        }

    private fun cancelSearch(searchView: SearchView) {
        searchView.onActionViewCollapsed()
        searchView.clearFocus()
    }

}
