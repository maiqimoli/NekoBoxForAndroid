package io.nekohasekai.sagernet.ui

import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.google.android.material.chip.Chip
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.scrollTo
import io.nekohasekai.sagernet.ktx.snackbar
import java.util.Date

class ConfigurationUiController(private val owner: ConfigurationFragment) {
    private var profileQuery = ""
    private var profileFilter = ProfileFilter.fromKey(DataStore.profileListFilter)
    private var pendingScrollProfileId: Long? = null

    fun onQueryTextChange(query: String): Boolean {
        if (query != profileQuery) owner.exitBatchSelection()
        profileQuery = query
        owner.getCurrentProfileGroupFragment()?.let(::applyFiltersTo)
        return false
    }

    fun bindFilters(view: View) {
        if (owner.select) {
            profileQuery = ""
            profileFilter = ProfileFilter.ALL
            view.findViewById<View>(R.id.profile_filter_scroll).isGone = true
            return
        }
        owner.filterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            owner.exitBatchSelection()
            profileFilter = when (checkedIds.firstOrNull()) {
                R.id.filter_favorites -> ProfileFilter.FAVORITES
                R.id.filter_recent -> ProfileFilter.RECENT
                R.id.filter_fast -> ProfileFilter.FAST
                R.id.filter_hk -> ProfileFilter.HK
                R.id.filter_us -> ProfileFilter.US
                R.id.filter_jp -> ProfileFilter.JP
                else -> ProfileFilter.ALL
            }
            DataStore.profileListFilter = profileFilter.key
            owner.getCurrentProfileGroupFragment()?.let(::applyFiltersTo)
        }
        owner.filterGroup.check(
            when (profileFilter) {
                ProfileFilter.FAVORITES -> R.id.filter_favorites
                ProfileFilter.RECENT -> R.id.filter_recent
                ProfileFilter.FAST -> R.id.filter_fast
                ProfileFilter.HK -> R.id.filter_hk
                ProfileFilter.US -> R.id.filter_us
                ProfileFilter.JP -> R.id.filter_jp
                ProfileFilter.ALL -> R.id.filter_all
            }
        )
    }

    fun applyFiltersTo(fragment: ProfileGroupFragment) {
        fragment.adapter?.setFilters(
            if (owner.select) "" else profileQuery,
            if (owner.select) ProfileFilter.ALL else profileFilter,
        )
    }

    fun focusCurrentProfile() {
        val profileId = DataStore.currentProfile.takeIf { it > 0L } ?: DataStore.selectedProxy
        if (profileId <= 0L) {
            owner.snackbar(R.string.current_profile_unavailable).show()
            return
        }
        owner.exitBatchSelection()
        pendingScrollProfileId = profileId
        profileQuery = ""
        owner.toolbar.findViewById<SearchView>(R.id.action_search)?.apply {
            setQuery("", false)
            clearFocus()
        }
        profileFilter = ProfileFilter.ALL
        DataStore.profileListFilter = ProfileFilter.ALL.key
        if (owner.isFilterGroupInitialized()) owner.filterGroup.check(R.id.filter_all)
        runOnDefaultDispatcher {
            val profile = SagerDatabase.proxyDao.getById(profileId)
            onMainDispatcher {
                if (profile == null) {
                    pendingScrollProfileId = null
                    owner.snackbar(R.string.current_profile_unavailable).show()
                    return@onMainDispatcher
                }
                DataStore.selectedGroup = profile.groupId
                val groupIndex = owner.adapter.groupList.indexOfFirst { it.id == profile.groupId }
                if (groupIndex >= 0) {
                    owner.groupPager.setCurrentItem(groupIndex, false)
                    owner.getCurrentProfileGroupFragment()?.let(::applyFiltersTo)
                    owner.getCurrentProfileGroupFragment()?.let(::consumePendingScroll)
                } else {
                    owner.adapter.reload()
                }
            }
        }
    }

    fun consumePendingScroll(fragment: ProfileGroupFragment) {
        val profileId = pendingScrollProfileId ?: return
        val listAdapter = fragment.adapter ?: return
        val index = listAdapter.configurationIdList.indexOf(profileId)
        if (index < 0) return
        pendingScrollProfileId = null
        fragment.configurationListView.post {
            fragment.configurationListView.scrollTo(index, true)
        }
    }

    fun updateFilterCounts(counts: ProfileFilterCounts) {
        if (owner.select || !owner.isFilterGroupInitialized()) return
        val fastChip = owner.filterGroup.findViewById<Chip>(R.id.filter_fast)
        val hasFastProfiles = counts.fast > 0
        fastChip?.isVisible = hasFastProfiles
        if (!hasFastProfiles && profileFilter == ProfileFilter.FAST) {
            profileFilter = ProfileFilter.ALL
            DataStore.profileListFilter = ProfileFilter.ALL.key
            owner.filterGroup.check(R.id.filter_all)
        }
        val labels = listOf(
            Triple(R.id.filter_all, R.string.filter_all, counts.all),
            Triple(R.id.filter_recent, R.string.filter_recent, counts.recent),
            Triple(R.id.filter_hk, R.string.filter_hk, counts.hk),
            Triple(R.id.filter_us, R.string.filter_us, counts.us),
            Triple(R.id.filter_jp, R.string.filter_jp, counts.jp),
            Triple(R.id.filter_fast, R.string.filter_fast, counts.fast),
            Triple(R.id.filter_favorites, R.string.filter_favorites, counts.favorites),
        )
        labels.forEach { (viewId, labelRes, count) ->
            owner.filterGroup.findViewById<Chip>(viewId)?.text = owner.getString(
                R.string.filter_count_format,
                owner.getString(labelRes),
                count,
            )
        }
    }

    fun bindGroupTab(tab: TabLayout.Tab, position: Int) {
        val group = owner.adapter.groupList.getOrNull(position) ?: return
        val customView = tab.customView ?: owner.layoutInflater.inflate(
            R.layout.layout_group_tab,
            owner.tabLayout,
            false,
        ).also { tab.customView = it }
        customView.findViewById<TextView>(R.id.group_tab_title).text = group.displayName()
        val statusView = customView.findViewById<TextView>(R.id.group_tab_status)
        val subscription = group.subscription
        if (group.type != GroupType.SUBSCRIPTION || subscription == null) {
            statusView.isGone = true
            return
        }

        val now = System.currentTimeMillis() / 1000L
        val status = SubscriptionStatus.from(subscription, now)
        val compactParts = mutableListOf<String>()
        status.traffic?.remainingBytes?.let { remaining ->
            compactParts += owner.getString(
                R.string.subscription_remaining_short,
                Formatter.formatFileSize(owner.requireContext(), remaining),
            )
        }
        status.lastUpdatedEpochSeconds?.let { updated ->
            compactParts += owner.getString(
                R.string.subscription_updated_short,
                android.text.format.DateFormat.format("M/d", Date(updated * 1000L)),
            )
        }
        when (status.expiryState) {
            SubscriptionExpiryState.EXPIRED -> compactParts += owner.getString(
                R.string.subscription_expired_short
            )
            SubscriptionExpiryState.ACTIVE -> status.expiresAtEpochSeconds?.let { expiresAt ->
                val days = ((expiresAt - now + 86399L) / 86400L).coerceAtLeast(1L)
                compactParts += if (days <= 30L) {
                    owner.getString(R.string.subscription_expires_days_short, days)
                } else {
                    owner.getString(
                        R.string.subscription_expires_date_short,
                        android.text.format.DateFormat.format("M/d", Date(expiresAt * 1000L)),
                    )
                }
            }
            SubscriptionExpiryState.UNKNOWN -> Unit
        }
        if (compactParts.isEmpty()) {
            compactParts += owner.getString(R.string.subscription_never_updated_short)
        }
        statusView.text = compactParts.joinToString(" · ")
        statusView.setTextColor(owner.requireContext().getColour(
            if (status.isExpired) R.color.cyber_coral_text else R.color.cyber_text_secondary
        ))
        statusView.isVisible = true
        TooltipCompat.setTooltipText(customView, statusView.text)
    }
}
