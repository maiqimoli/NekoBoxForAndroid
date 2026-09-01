package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class GroupPreference
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = R.attr.dropdownPreferenceStyle
) : SimpleMenuPreference(context, attrs, defStyle, 0) {

    private var groupNames: Map<Long, String> = emptyMap()

    init {
        runOnIoDispatcher {
            val groups = SagerDatabase.groupDao.allGroups()
            val names = groups.associate { it.id to it.displayName() }
            runOnMainDispatcher {
                groupNames = names
                entries = groups.map { it.displayName() }.toTypedArray()
                entryValues = groups.map { "${it.id}" }.toTypedArray()
                notifyChanged()
            }
        }
    }

    override fun getSummary(): CharSequence? {
        if (!value.isNullOrBlank() && value != "0") {
            return value.toLongOrNull()?.let(groupNames::get)
                ?: super.getSummary()
        }
        return super.getSummary()
    }

}
