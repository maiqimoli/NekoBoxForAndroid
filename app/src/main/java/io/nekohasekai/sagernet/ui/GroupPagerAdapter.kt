package io.nekohasekai.sagernet.ui

import android.view.View
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

/**
 * 分组 ViewPager 适配器。抽取自 ConfigurationFragment 以控制单文件规模。
 */
class GroupPagerAdapter(private val fragment: ConfigurationFragment) :
    FragmentStateAdapter(fragment),
    ProfileManager.Listener,
    GroupManager.Listener {

    var selectedGroupIndex = 0
    var groupList: ArrayList<ProxyGroup> = ArrayList()
    var groupFragments: HashMap<Long, ProfileGroupFragment> = HashMap()

    private fun updateTabMode() {
        fragment.tabLayout.tabMode = if (groupList.size <= 2) {
            TabLayout.MODE_FIXED
        } else {
            TabLayout.MODE_SCROLLABLE
        }
        fragment.tabLayout.tabGravity = TabLayout.GRAVITY_FILL
    }

    fun reload(now: Boolean = false) {

        if (!fragment.select) {
            fragment.groupPager.unregisterOnPageChangeCallback(fragment.updateSelectedCallback)
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

            var selectedGroup = fragment.selectedItem?.groupId ?: DataStore.currentGroupId()
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

            val runFunc = if (now) fragment.activity?.let { it::runOnUiThread }
            else fragment.groupPager::post
            if (runFunc != null) {
                runFunc {
                    groupList = newGroupList
                    updateTabMode()
                    notifyDataSetChanged()
                    if (set) fragment.groupPager.setCurrentItem(selectedGroupIndex, false)
                    val hideTab = groupList.size < 2
                    fragment.tabLayout.isGone = hideTab
                    fragment.toolbar.elevation = if (hideTab) 0F else dp2px(4).toFloat()
                    if (!fragment.select) {
                        fragment.groupPager.registerOnPageChangeCallback(fragment.updateSelectedCallback)
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
        fragment.tabLayout.post {
            groupList.add(group)
            updateTabMode()

            if (groupList.any { !it.ungrouped }) fragment.tabLayout.post {
                fragment.tabLayout.visibility = View.VISIBLE
            }

            notifyItemInserted(groupList.size - 1)
            fragment.tabLayout.getTabAt(groupList.size - 1)?.select()
        }
    }

    override suspend fun groupRemoved(groupId: Long) {
        val index = groupList.indexOfFirst { it.id == groupId }
        if (index == -1) return

        fragment.tabLayout.post {
            groupList.removeAt(index)
            updateTabMode()
            notifyItemRemoved(index)
        }
    }

    override suspend fun groupUpdated(group: ProxyGroup) {
        val index = groupList.indexOfFirst { it.id == group.id }
        if (index == -1) return

        fragment.tabLayout.post {
            groupList[index] = group
            fragment.tabLayout.getTabAt(index)?.let { fragment.bindGroupTab(it, index) }
        }
    }

    override suspend fun groupUpdated(groupId: Long) = Unit

    override suspend fun onAdd(profile: ProxyEntity) {
        if (groupList.find { it.id == profile.groupId } == null) {
            DataStore.selectedGroup = profile.groupId
            reload()
        }
    }

    override suspend fun onUpdated(data: io.nekohasekai.sagernet.aidl.TrafficData) = Unit

    override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) = Unit

    override suspend fun onRemoved(groupId: Long, profileId: Long) {
        val group = groupList.find { it.id == groupId } ?: return
        if (group.ungrouped && SagerDatabase.proxyDao.countByGroup(groupId) == 0L) {
            reload()
        }
    }
}
