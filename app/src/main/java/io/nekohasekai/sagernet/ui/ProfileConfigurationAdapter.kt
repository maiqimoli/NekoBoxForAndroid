package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutProfileListBinding
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.ktx.*
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
import io.nekohasekai.sagernet.utils.AutoRegionManager
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import moe.matsuri.nb4a.proxy.config.ConfigSettingActivity
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSSettingsActivity
import android.graphics.Color
import android.text.format.Formatter
import android.view.MenuItem
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isGone
import androidx.core.view.size

class ProfileConfigurationAdapter(private val owner: ProfileGroupFragment) : RecyclerView.Adapter<ConfigurationHolder>(),
    ProfileManager.Listener,
    GroupManager.Listener,
    UndoSnackbarManager.Interface<ProxyEntity> {

    init {
        setHasStableIds(true)
    }

    var configurationIdList: MutableList<Long> = mutableListOf()
    val configurationList = HashMap<Long, ProxyEntity>()
    private var sourceProfileIds: List<Long> = emptyList()
    private var profileRegions: Map<Long, String?> = emptyMap()
    private var searchQuery = ""
    private var profileFilter = ProfileFilter.ALL
    private val pendingRemovalIds = HashSet<Long>()
    private var favoriteProfileIds = ProfileUiState.favoriteIds()
    private var recentProfileIds = ProfileUiState.recentIds()

    val isFiltering: Boolean
        get() = searchQuery.isNotEmpty() || profileFilter != ProfileFilter.ALL

    private fun getItem(profileId: Long): ProxyEntity {
        var profile = configurationList[profileId]
        if (profile == null) {
            profile = ProfileManager.getProfile(profileId)
            if (profile != null) {
                configurationList[profileId] = profile
            }
        }
        return profile!!
    }

    private fun getItemAt(index: Int) = getItem(configurationIdList[index])

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ConfigurationHolder {
        return ConfigurationHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.layout_profile, parent, false),
            owner
        )
    }

    override fun getItemId(position: Int): Long {
        return configurationIdList[position]
    }

    override fun onBindViewHolder(holder: ConfigurationHolder, position: Int) {
        try {
            holder.bind(getItemAt(position))
        } catch (ignored: NullPointerException) { // when group deleted
        }
    }

    override fun onBindViewHolder(
        holder: ConfigurationHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
        } else {
            try {
                val item = getItemAt(position)
                for (payload in payloads) {
                    when (payload) {
                        ConfigurationPayload.LATENCY -> holder.updateStatus(item)
                        ConfigurationPayload.TRAFFIC -> holder.updateTraffic(item)
                        ConfigurationPayload.SELECTED -> holder.updateSelected(item)
                        ConfigurationPayload.FAVORITE -> holder.updateFavorite(item)
                        else -> holder.bind(item)
                    }
                }
            } catch (ignored: Exception) {
            }
        }
    }

    override fun getItemCount(): Int {
        return configurationIdList.size
    }

    private val updated = HashSet<ProxyEntity>()

    fun setFilters(query: String, filter: ProfileFilter) {
        searchQuery = query.trim().lowercase()
        profileFilter = filter
        submitVisibleProfileIds(visibleProfileIds())
    }

    fun filter(name: String) = setFilters(name, profileFilter)

    fun isFavorite(profileId: Long) = profileId in favoriteProfileIds

    fun areAllFavorites(profileIds: Collection<Long>) =
        profileIds.isNotEmpty() && profileIds.all { it in favoriteProfileIds }

    fun refreshUiState(profileId: Long? = null) {
        favoriteProfileIds = ProfileUiState.favoriteIds()
        recentProfileIds = ProfileUiState.recentIds()
        submitVisibleProfileIds(visibleProfileIds())
        profileId?.let { id ->
            val index = configurationIdList.indexOf(id)
            if (index >= 0) notifyItemChanged(index, ConfigurationPayload.FAVORITE)
        } ?: notifyItemRangeChanged(0, itemCount, ConfigurationPayload.FAVORITE)
    }

    fun refreshTestAges() {
        submitVisibleProfileIds(visibleProfileIds())
        notifyItemRangeChanged(0, itemCount, ConfigurationPayload.LATENCY)
    }

    private fun visibleProfileIds(): List<Long> {
        val favoriteIds = favoriteProfileIds
        val recentIds = recentProfileIds
        val recentSet = recentIds.toSet()
        val visible = sourceProfileIds.filter { id ->
            if (id in pendingRemovalIds) return@filter false
            val profile = configurationList[id] ?: return@filter false
            val matchesText = searchQuery.isEmpty() ||
                    profile.displayName().lowercase().contains(searchQuery) ||
                    profile.displayType().lowercase().contains(searchQuery) ||
                    profile.displayAddress().lowercase().contains(searchQuery)
            val matchesCategory = when (profileFilter) {
                ProfileFilter.ALL -> true
                ProfileFilter.FAVORITES -> id in favoriteIds
                ProfileFilter.RECENT -> id in recentSet
                ProfileFilter.FAST -> profile.status == 1 && profile.ping <= 100 &&
                        ProfileUiState.isTestFresh(id)
                else -> profileRegions[id] == profileFilter.regionCode
            }
            matchesText && matchesCategory
        }
        if (profileFilter != ProfileFilter.RECENT) return visible
        val visibleSet = visible.toSet()
        return recentIds.filter { it in visibleSet }
    }

    private fun submitVisibleProfileIds(newIds: List<Long>) {
        val oldList = ArrayList(configurationIdList)
        val diff = DiffUtil.calculateDiff(ProfileDiffCallback(oldList, newIds))
        configurationIdList.clear()
        configurationIdList.addAll(newIds)
        diff.dispatchUpdatesTo(this)
        updateEmptyView()
        updateFilterCounts()
        (owner.parentFragment as? ConfigurationFragment)?.consumePendingScroll(owner)
    }

    private fun updateFilterCounts() {
        val activeIds = sourceProfileIds.filterNot { it in pendingRemovalIds }
        val favorites = favoriteProfileIds
        val recent = recentProfileIds.toSet()
        fun countRegion(code: String) = activeIds.count { profileRegions[it] == code }
        val counts = ProfileFilterCounts(
            all = activeIds.size,
            favorites = activeIds.count { it in favorites },
            recent = activeIds.count { it in recent },
            fast = activeIds.count { id ->
                configurationList[id]?.let {
                    it.status == 1 && it.ping <= 100 && ProfileUiState.isTestFresh(id)
                } == true
            },
            hk = countRegion("HK"),
            us = countRegion("US"),
            jp = countRegion("JP"),
        )
        if (owner.proxyGroup.id == DataStore.selectedGroup) {
            (owner.parentFragment as? ConfigurationFragment)?.updateFilterCounts(counts)
        }
    }

    fun move(from: Int, to: Int) {
        val first = getItemAt(from)
        var previousOrder = first.userOrder
        val (step, range) = if (from < to) Pair(1, from until to) else Pair(
            -1, to + 1 downTo from
        )
        for (i in range) {
            val next = getItemAt(i + step)
            val order = next.userOrder
            next.userOrder = previousOrder
            previousOrder = order
            configurationIdList[i] = next.id
            updated.add(next)
        }
        first.userOrder = previousOrder
        configurationIdList[to] = first.id
        updated.add(first)
        notifyItemMoved(from, to)
    }

    fun commitMove() = runOnDefaultDispatcher {
        updated.forEach { SagerDatabase.proxyDao.updateProxy(it) }
        updated.clear()
    }

    fun remove(pos: Int) {
        if (pos < 0) return
        pendingRemovalIds.add(configurationIdList[pos])
        submitVisibleProfileIds(visibleProfileIds())
    }

    fun removeByIds(profileIds: Set<Long>) {
        val actions = profileIds.mapNotNull { id ->
            val profile = configurationList[id] ?: return@mapNotNull null
            val index = configurationIdList.indexOf(id).takeIf { it >= 0 }
                ?: sourceProfileIds.indexOf(id).takeIf { it >= 0 }
                ?: return@mapNotNull null
            index to profile
        }
        if (actions.isEmpty()) return
        pendingRemovalIds.addAll(actions.map { it.second.id })
        submitVisibleProfileIds(visibleProfileIds())
        if (owner.isUndoManagerInitialized()) owner.undoManager.remove(actions)
    }

    override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
        for ((_, item) in actions) {
            owner.configurationListView.post {
                pendingRemovalIds.remove(item.id)
                configurationList[item.id] = item
                if (item.id !in sourceProfileIds) {
                    sourceProfileIds = sourceProfileIds + item.id
                }
                submitVisibleProfileIds(visibleProfileIds())
            }
        }
    }

    override fun commit(actions: List<Pair<Int, ProxyEntity>>) {
        val profiles = actions.map { it.second }
        profiles.forEach { ProfileUiState.removeProfile(it.id) }
        runOnDefaultDispatcher {
            for (entity in profiles) {
                ProfileManager.deleteProfile(entity.groupId, entity.id)
            }
        }
    }

    override suspend fun onAdd(profile: ProxyEntity) {
        if (profile.groupId != owner.proxyGroup.id) return
        val region = runCatching {
            AutoRegionManager.resolveRegionCode(profile)
        }.getOrNull()

        owner.configurationListView.post {
            if (owner.isUndoManagerInitialized()) {
                owner.undoManager.flush()
            }
            configurationList[profile.id] = profile
            if (profile.id !in sourceProfileIds) {
                sourceProfileIds = sourceProfileIds + profile.id
            }
            profileRegions = profileRegions + (profile.id to region)
            submitVisibleProfileIds(visibleProfileIds())
        }
    }

    override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) {
        if (profile.groupId != owner.proxyGroup.id) return
        val region = runCatching {
            AutoRegionManager.resolveRegionCode(profile)
        }.getOrNull()
        owner.configurationListView.post {
            if (owner.isUndoManagerInitialized()) {
                owner.undoManager.flush()
            }
            val oldProfile = configurationList[profile.id]
            configurationList[profile.id] = profile
            profileRegions = profileRegions + (profile.id to region)
            submitVisibleProfileIds(visibleProfileIds())
            val index = configurationIdList.indexOf(profile.id)
            // 刷新延迟 + 选中态（切换选中时旧节点需取消高亮）
            if (index >= 0) {
                notifyItemChanged(
                    index, listOf(
                        ConfigurationPayload.LATENCY,
                        ConfigurationPayload.SELECTED
                    )
                )
            }
            //
            if (noTraffic && oldProfile != null) {
                runOnDefaultDispatcher {
                    onUpdated(
                        TrafficData(
                            id = profile.id,
                            rx = oldProfile.rx,
                            tx = oldProfile.tx
                        )
                    )
                }
            }
        }
    }

    override suspend fun onUpdated(data: TrafficData) {
        try {
            val index = configurationIdList.indexOf(data.id)
            if (index != -1) {
                owner.configurationListView.post {
                    notifyItemChanged(index, ConfigurationPayload.TRAFFIC)
                }
            }
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    override suspend fun onRemoved(groupId: Long, profileId: Long) {
        if (groupId != owner.proxyGroup.id) return

        ProfileUiState.removeProfile(profileId)

        owner.configurationListView.post {
            pendingRemovalIds.remove(profileId)
            sourceProfileIds = sourceProfileIds.filterNot { it == profileId }
            configurationList.remove(profileId)
            profileRegions = profileRegions - profileId
            submitVisibleProfileIds(visibleProfileIds())
        }
    }

    override suspend fun groupAdd(group: ProxyGroup) = Unit
    override suspend fun groupRemoved(groupId: Long) = Unit

    override suspend fun groupUpdated(group: ProxyGroup) {
        if (group.id != owner.proxyGroup.id) return
        owner.proxyGroup = group
        reloadProfiles()
    }

    override suspend fun groupUpdated(groupId: Long) {
        if (groupId != owner.proxyGroup.id) return
        owner.proxyGroup = SagerDatabase.groupDao.getById(groupId)!!
        reloadProfiles()
    }

    fun reloadProfiles() {
        var newProfiles = SagerDatabase.proxyDao.getByGroup(owner.proxyGroup.id)
        when (owner.proxyGroup.order) {
            GroupOrder.BY_NAME -> {
                newProfiles = newProfiles.sortedBy { it.displayName() }

            }

            GroupOrder.BY_DELAY -> {
                newProfiles =
                    newProfiles.sortedBy { if (it.status == 1) it.ping else 114514 }
            }
        }

        configurationList.clear()
        configurationList.putAll(newProfiles.associateBy { it.id })
        val newProfileIds = newProfiles.map { it.id }
        sourceProfileIds = newProfileIds
        profileRegions = newProfiles.associate { profile ->
            profile.id to runCatching {
                AutoRegionManager.resolveRegionCode(profile)
            }.getOrNull()
        }

        owner.configurationListView.post {
            val visibleIds = visibleProfileIds()
            val selectedProfileIndex = if (owner.selected) {
                val selectedProxy = owner.selectedItem?.id ?: DataStore.selectedProxy
                visibleIds.indexOf(selectedProxy)
            } else {
                -1
            }
            submitVisibleProfileIds(visibleIds)

            if (selectedProfileIndex != -1) {
                owner.configurationListView.scrollTo(selectedProfileIndex, true)
            } else if (visibleIds.isNotEmpty()) {
                owner.configurationListView.scrollTo(0, true)
            }

        }
    }

    fun updateEmptyView() {
        val emptyView = owner.emptyView ?: return
        val isEmpty = configurationIdList.isEmpty()
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        owner.configurationListView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        emptyView.findViewById<TextView>(R.id.empty_title).setText(
            if (isFiltering) R.string.profile_filter_empty_title else R.string.profile_empty_title
        )
        emptyView.findViewById<TextView>(R.id.empty_subtitle).setText(
            if (isFiltering) R.string.profile_filter_empty_subtitle else R.string.profile_empty_subtitle
        )
        emptyView.findViewById<View>(R.id.empty_add_button).isVisible = !isFiltering
    }

}
