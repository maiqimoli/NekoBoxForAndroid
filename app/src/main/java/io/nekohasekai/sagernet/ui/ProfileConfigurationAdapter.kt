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
import io.nekohasekai.sagernet.database.ProxyOrderUpdate
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

class ProfileConfigurationAdapter(
    private val owner: ProfileGroupFragment,
    private val canWriteOrder: Boolean,
) : RecyclerView.Adapter<ConfigurationHolder>(),
    ProfileManager.Listener,
    GroupManager.Listener,
    UndoSnackbarManager.Interface<ProxyEntity> {

    init {
        setHasStableIds(true)
    }

    var configurationIdList: MutableList<Long> = mutableListOf()
    val configurationList = HashMap<Long, ProxyEntity>()
    private val liveTraffic = HashMap<Long, TrafficData>()
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
        return configurationList[profileId]
            ?: throw NoSuchElementException("Profile $profileId is missing from the adapter snapshot")
    }

    private fun getItemAt(index: Int) = getItem(configurationIdList[index])

    private fun withPendingUserOrder(profile: ProxyEntity): ProxyEntity {
        val order = pendingMoveUpdates[profile.id]?.userOrder
            ?: ProfileOrderCoordinator.currentOrders(owner.proxyGroup.id)[profile.id]
        return if (order != null && order != profile.userOrder) {
            profile.copy(userOrder = order)
        } else {
            profile
        }
    }

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
            val item = getItemAt(position)
            holder.bind(item, liveTraffic[item.id])
        } catch (ignored: NoSuchElementException) { // snapshot changed while the row was binding
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
                        ConfigurationPayload.TRAFFIC -> holder.updateTraffic(item, liveTraffic[item.id])
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

    private val pendingMoveUpdates = LinkedHashMap<Long, ProxyOrderUpdate>()
    private val orderSession = if (canWriteOrder) {
        ProfileOrderCoordinator.attachWriter(owner.proxyGroup.id)
    } else {
        null
    }
    private val reloadGeneration = AtomicLong()

    fun setFilters(query: String, filter: ProfileFilter) {
        searchQuery = query.trim().lowercase()
        profileFilter = filter
        submitVisibleProfileIds(visibleProfileIds())
    }

    fun filter(name: String) = setFilters(name, profileFilter)

    fun isFavorite(profileId: Long) = profileId in favoriteProfileIds

    fun isRecent(profileId: Long) = profileId in recentProfileIds

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
        if (!canWriteOrder || from !in configurationIdList.indices ||
            to !in configurationIdList.indices || from == to
        ) {
            return
        }
        val first = getItemAt(from)
        var previousOrder = first.userOrder
        val (step, range) = if (from < to) Pair(1, from until to) else Pair(
            -1, from downTo to + 1
        )
        for (i in range) {
            val next = getItemAt(i + step)
            val order = next.userOrder
            next.userOrder = previousOrder
            previousOrder = order
            configurationIdList[i] = next.id
            pendingMoveUpdates[next.id] = ProxyOrderUpdate(next.id, next.userOrder)
        }
        first.userOrder = previousOrder
        configurationIdList[to] = first.id
        pendingMoveUpdates[first.id] = ProxyOrderUpdate(first.id, first.userOrder)
        // Dragging is disabled while filtering, but pending removals can still leave holes in the
        // visible list. Replace only visible slots so a later filter/reload does not resurrect the
        // pre-drag source order or move a hidden row.
        sourceProfileIds = mergeVisibleProfileOrder(sourceProfileIds, configurationIdList)
        notifyItemMoved(from, to)
    }

    fun commitMove() {
        if (pendingMoveUpdates.isEmpty()) return
        val session = orderSession ?: return
        val updates = pendingMoveUpdates.values.toList()
        if (ProfileOrderCoordinator.submit(session, updates)) {
            // The coordinator now owns immutable copies until the database acknowledges them.
            // Clear only after acceptance so a stale lease does not silently discard a drag.
            pendingMoveUpdates.clear()
        } else {
            Logs.w("Ignored a profile-order commit from a stale adapter")
        }
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
            configurationList[profile.id] = withPendingUserOrder(profile)
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
            val pendingProfile = withPendingUserOrder(profile)
            val mergedOrder = resolveUpdatedProfileOrder(
                incomingOrder = pendingProfile.userOrder,
                currentOrder = oldProfile?.userOrder,
            )
            configurationList[profile.id] = if (mergedOrder != pendingProfile.userOrder) {
                pendingProfile.copy(userOrder = mergedOrder)
            } else {
                pendingProfile
            }
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
        owner.configurationListView.post {
            try {
                liveTraffic[data.id] = data
                val index = configurationIdList.indexOf(data.id)
                if (index != -1) {
                    notifyItemChanged(index, ConfigurationPayload.TRAFFIC)
                }
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    }

    override suspend fun onRemoved(groupId: Long, profileId: Long) {
        if (groupId != owner.proxyGroup.id) return

        ProfileUiState.removeProfile(profileId)

        owner.configurationListView.post {
            pendingRemovalIds.remove(profileId)
            sourceProfileIds = sourceProfileIds.filterNot { it == profileId }
            configurationList.remove(profileId)
            liveTraffic.remove(profileId)
            profileRegions = profileRegions - profileId
            submitVisibleProfileIds(visibleProfileIds())
        }
    }

    override suspend fun groupAdd(group: ProxyGroup) = Unit
    override suspend fun groupRemoved(groupId: Long) = Unit

    override suspend fun groupUpdated(group: ProxyGroup) {
        if (group.id != owner.proxyGroup.id) return
        reloadProfiles(group)
    }

    override suspend fun groupUpdated(groupId: Long) {
        if (groupId != owner.proxyGroup.id) return
        val group = withContext(Dispatchers.IO) {
            SagerDatabase.groupDao.getById(groupId)
        } ?: return
        reloadProfiles(group)
    }

    fun reloadProfiles(group: ProxyGroup = owner.proxyGroup) {
        if (group.id != owner.proxyGroup.id) return
        val lifecycleOwner = runCatching { owner.viewLifecycleOwner }.getOrNull() ?: return
        val generation = reloadGeneration.incrementAndGet()
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var readToken: ProfileOrderReadToken
            var databaseProfiles: List<ProxyEntity>
            while (true) {
                readToken = ProfileOrderCoordinator.captureRead(group.id)
                val queriedProfiles = SagerDatabase.proxyDao.getByGroup(group.id)
                val pendingOrders = ProfileOrderCoordinator.pendingOrders(readToken) ?: continue
                databaseProfiles = queriedProfiles.map { profile ->
                    pendingOrders[profile.id]?.let { order -> profile.copy(userOrder = order) }
                        ?: profile
                }
                break
            }
            val newProfileRegions = databaseProfiles.associate { profile ->
                profile.id to runCatching {
                    AutoRegionManager.resolveRegionCode(profile)
                }.getOrNull()
            }

            withContext(Dispatchers.Main.immediate) {
                if (generation != reloadGeneration.get()) return@withContext
                if (!ProfileOrderCoordinator.isCurrent(readToken)) {
                    // The query raced a submitted drag or a completed write. Retry with one
                    // coherent database/pending snapshot. Readers do not need a writer lease.
                    reloadProfiles(group)
                    return@withContext
                }
                // A drag can happen after the IO query but before this main-thread apply. Overlay
                // the adapter-local values last and use the current source order as the tie-breaker
                // for duplicate userOrder values.
                val localOrders = pendingMoveUpdates.mapValues { it.value.userOrder }
                val newProfiles = sortProfilesForGroup(
                    databaseProfiles.map { profile ->
                        localOrders[profile.id]?.let { order ->
                            profile.copy(userOrder = order)
                        } ?: profile
                    },
                    group.order,
                    sourceProfileIds,
                )
                val newConfigurationList = newProfiles.associateBy { it.id }
                val newProfileIds = newProfiles.map { it.id }
                owner.proxyGroup = group
                configurationList.clear()
                configurationList.putAll(newConfigurationList)
                sourceProfileIds = newProfileIds
                liveTraffic.keys.retainAll(newProfileIds.toSet())
                profileRegions = newProfileRegions
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
    }

    fun updateEmptyView() {
        val emptyView = owner.emptyView ?: return
        val isEmpty = configurationIdList.isEmpty()
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        owner.configurationListView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        val recentEmpty = profileFilter == ProfileFilter.RECENT && searchQuery.isEmpty()
        emptyView.findViewById<TextView>(R.id.empty_title).setText(when {
            recentEmpty -> R.string.recent_records_empty_title
            isFiltering -> R.string.profile_filter_empty_title
            else -> R.string.profile_empty_title
        })
        emptyView.findViewById<TextView>(R.id.empty_subtitle).setText(when {
            recentEmpty -> R.string.recent_records_empty_subtitle
            isFiltering -> R.string.profile_filter_empty_subtitle
            else -> R.string.profile_empty_subtitle
        })
        emptyView.findViewById<View>(R.id.empty_add_button).isVisible = !isFiltering
    }

}

internal fun resolveUpdatedProfileOrder(incomingOrder: Long, currentOrder: Long?): Long =
    currentOrder ?: incomingOrder

/** Reorders visible slots while retaining hidden/deleted-pending rows at their source positions. */
internal fun mergeVisibleProfileOrder(sourceIds: List<Long>, visibleIds: List<Long>): List<Long> {
    if (sourceIds.isEmpty() || visibleIds.isEmpty()) return sourceIds
    val visibleSet = visibleIds.toHashSet()
    val orderedVisible = visibleIds.iterator()
    return sourceIds.map { sourceId ->
        if (sourceId in visibleSet && orderedVisible.hasNext()) orderedVisible.next() else sourceId
    }
}

internal fun sortProfilesForGroup(
    profiles: List<ProxyEntity>,
    groupOrder: Int,
    preferredIds: List<Long> = emptyList(),
): List<ProxyEntity> {
    val preferredRank = preferredIds.withIndex().associate { (index, id) -> id to index }
    fun rank(profile: ProxyEntity) = preferredRank[profile.id] ?: Int.MAX_VALUE
    return when (groupOrder) {
        GroupOrder.BY_NAME -> profiles.sortedWith(
            compareBy<ProxyEntity>({ it.displayName() }, { it.userOrder }, { rank(it) }, { it.id })
        )

        GroupOrder.BY_DELAY -> profiles.sortedWith(
            compareBy<ProxyEntity>(
                { if (it.status == 1) it.ping else 114514 },
                { it.userOrder },
                { rank(it) },
                { it.id },
            )
        )

        else -> profiles.sortedWith(
            compareBy<ProxyEntity>({ it.userOrder }, { rank(it) }, { it.id })
        )
    }
}

internal data class ProfileOrderSession(
    val groupId: Long,
    val generation: Long,
)

internal data class ProfileOrderReadToken(
    val groupId: Long,
    val generation: Long,
)

internal data class ProfileOrderWriterAttachment(
    val session: ProfileOrderSession,
    val startWriter: Boolean,
)

internal data class ProfileOrderSubmission(
    val accepted: Boolean,
    val startWriter: Boolean,
)

internal data class VersionedProfileOrderUpdate(
    val update: ProxyOrderUpdate,
    val generation: Long,
)

internal data class ProfileOrderUpdateSnapshot(
    val entries: List<VersionedProfileOrderUpdate>,
) {
    val updates = entries.map { it.update }
}

/** Keeps immutable order-only values so an in-flight acknowledgement cannot clear a newer drag. */
internal class PendingProfileOrderUpdates {
    private val updates = LinkedHashMap<Long, VersionedProfileOrderUpdate>()
    private var lastGeneration = 0L

    fun record(generation: Long, changed: Collection<ProxyOrderUpdate>) {
        require(generation > lastGeneration) { "Profile order generations must be monotonic" }
        lastGeneration = generation
        changed.forEach { update ->
            updates[update.profileId] = VersionedProfileOrderUpdate(update.copy(), generation)
        }
    }

    fun isEmpty() = updates.isEmpty()

    fun snapshot(): ProfileOrderUpdateSnapshot? = updates.values
        .takeIf { it.isNotEmpty() }
        ?.map { it.copy(update = it.update.copy()) }
        ?.let(::ProfileOrderUpdateSnapshot)

    fun acknowledge(committed: ProfileOrderUpdateSnapshot) {
        committed.entries.forEach { entry ->
            if (updates[entry.update.profileId]?.generation == entry.generation) {
                updates.remove(entry.update.profileId)
            }
        }
    }

    fun currentOrders(): Map<Long, Long> = updates.values.associate { entry ->
        entry.update.profileId to entry.update.userOrder
    }
}

/**
 * Monitor-confined state for one group. It is separate from the Android dispatcher wrapper so its
 * lease, acknowledgement, and retry transitions stay deterministic and unit-testable.
 */
internal class ProfileOrderGroupState(private val groupId: Long) {
    private var generation = 0L
    private var activeSessionGeneration = 0L
    private var writerRunning = false
    private val pending = PendingProfileOrderUpdates()

    fun attachWriter(): ProfileOrderWriterAttachment {
        val sessionGeneration = advanceGeneration()
        activeSessionGeneration = sessionGeneration
        return ProfileOrderWriterAttachment(
            session = ProfileOrderSession(groupId, sessionGeneration),
            startWriter = requestWriter(),
        )
    }

    fun submit(
        session: ProfileOrderSession,
        updates: Collection<ProxyOrderUpdate>,
    ): ProfileOrderSubmission {
        if (session.groupId != groupId || activeSessionGeneration != session.generation) {
            return ProfileOrderSubmission(accepted = false, startWriter = false)
        }
        if (updates.isEmpty()) {
            return ProfileOrderSubmission(accepted = true, startWriter = false)
        }
        val updateGeneration = advanceGeneration()
        pending.record(updateGeneration, updates)
        return ProfileOrderSubmission(
            accepted = true,
            startWriter = requestWriter(),
        )
    }

    fun captureRead() = ProfileOrderReadToken(groupId, generation)

    fun pendingOrders(token: ProfileOrderReadToken): Map<Long, Long>? =
        if (isCurrent(token)) pending.currentOrders() else null

    fun currentOrders(): Map<Long, Long> = pending.currentOrders()

    fun isCurrent(token: ProfileOrderReadToken) =
        token.groupId == groupId && token.generation == generation

    fun takeWriteSnapshot(): ProfileOrderUpdateSnapshot? = pending.snapshot().also { snapshot ->
        if (snapshot == null) {
            writerRunning = false
        }
    }

    fun acknowledge(snapshot: ProfileOrderUpdateSnapshot) {
        pending.acknowledge(snapshot)
        // Invalidate reads even if every entry was superseded while the write was in flight.
        advanceGeneration()
    }

    fun continueAfterRetryRound(): Boolean {
        if (!pending.isEmpty()) return true
        writerRunning = false
        return false
    }

    fun writerCancelled() {
        writerRunning = false
    }

    internal fun isWriterRunningForTest() = writerRunning

    private fun requestWriter(): Boolean {
        if (pending.isEmpty()) return false
        if (writerRunning) return false
        writerRunning = true
        return true
    }

    private fun advanceGeneration(): Long {
        generation = Math.incrementExact(generation)
        return generation
    }
}

/**
 * Process-wide, per-group serialization for profile ordering.
 *
 * The state outlives RecyclerView adapters, rejects commits from superseded adapters, and retains
 * failed writes for a later adapter. Room writes use only profile id and order, so an old UI
 * snapshot cannot overwrite unrelated profile fields.
 */
internal object ProfileOrderCoordinator {
    private const val MAX_WRITE_ATTEMPTS = 4
    private const val INITIAL_RETRY_DELAY_MS = 100L
    private const val MAX_RETRY_DELAY_MS = 800L
    private const val RETRY_ROUND_DELAY_MS = 5_000L

    private val monitor = Any()
    private val groups = HashMap<Long, ProfileOrderGroupState>()

    fun attachWriter(groupId: Long): ProfileOrderSession {
        val attachment = synchronized(monitor) {
            groups.getOrPut(groupId) { ProfileOrderGroupState(groupId) }.attachWriter()
        }
        if (attachment.startWriter) launchWriter(groupId)
        return attachment.session
    }

    fun submit(
        session: ProfileOrderSession,
        updates: Collection<ProxyOrderUpdate>,
    ): Boolean {
        val submission = synchronized(monitor) {
            groups[session.groupId]?.submit(session, updates)
                ?: ProfileOrderSubmission(accepted = false, startWriter = false)
        }
        if (submission.startWriter) launchWriter(session.groupId)
        return submission.accepted
    }

    fun captureRead(groupId: Long): ProfileOrderReadToken = synchronized(monitor) {
        groups.getOrPut(groupId) { ProfileOrderGroupState(groupId) }.captureRead()
    }

    fun currentOrders(groupId: Long): Map<Long, Long> = synchronized(monitor) {
        groups[groupId]?.currentOrders().orEmpty()
    }

    fun pendingOrders(token: ProfileOrderReadToken): Map<Long, Long>? =
        synchronized(monitor) {
            groups[token.groupId]?.pendingOrders(token)
        }

    fun isCurrent(token: ProfileOrderReadToken): Boolean = synchronized(monitor) {
        groups[token.groupId]?.isCurrent(token) == true
    }

    private fun launchWriter(groupId: Long) {
        runOnIoDispatcher { drain(groupId) }
    }

    private suspend fun drain(groupId: Long) {
        var failures = 0
        while (true) {
            val snapshot = synchronized(monitor) {
                groups[groupId]?.takeWriteSnapshot()
            } ?: return

            try {
                SagerDatabase.proxyDao.updateUserOrders(groupId, snapshot.updates)
            } catch (failure: Exception) {
                if (failure is CancellationException) {
                    synchronized(monitor) {
                        groups[groupId]?.writerCancelled()
                    }
                    throw failure
                }
                Logs.w(failure)
                failures++
                if (failures >= MAX_WRITE_ATTEMPTS) {
                    val shouldContinue = synchronized(monitor) {
                        groups[groupId]?.continueAfterRetryRound() ?: return
                    }
                    if (shouldContinue) {
                        failures = 0
                        // Keep a writer attached to every non-empty pending set. A transient Room
                        // failure must not leave the last drag only in memory until another page
                        // attachment happens, but space retry rounds to avoid a hot failure loop.
                        delay(RETRY_ROUND_DELAY_MS)
                        continue
                    }
                    return
                }
                val retryDelay = (INITIAL_RETRY_DELAY_MS shl (failures - 1))
                    .coerceAtMost(MAX_RETRY_DELAY_MS)
                delay(retryDelay)
                continue
            }

            synchronized(monitor) {
                groups[groupId]?.acknowledge(snapshot) ?: return
            }
            failures = 0
        }
    }
}
