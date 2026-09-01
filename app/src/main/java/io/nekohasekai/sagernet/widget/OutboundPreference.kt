package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class OutboundPreference
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = R.attr.dropdownPreferenceStyle
) : SimpleMenuPreference(context, attrs, defStyle, 0) {

    private var requestedOutboundId = 0L
    private var loadedOutboundId = 0L
    private var outboundName: String? = null

    init {
        setEntries(R.array.outbound_entry)
        setEntryValues(R.array.outbound_value)
    }

    override fun getSummary(): CharSequence? {
        if (value == "3") {
            val routeOutbound = DataStore.profileCacheStore.getLong(key + "Long") ?: 0
            if (routeOutbound > 0) {
                if (loadedOutboundId == routeOutbound) {
                    outboundName?.let { return it }
                } else if (requestedOutboundId != routeOutbound) {
                    requestedOutboundId = routeOutbound
                    runOnIoDispatcher {
                        val name = ProfileManager.getProfile(routeOutbound)?.displayName()
                        runOnMainDispatcher {
                            val currentId = DataStore.profileCacheStore
                                .getLong(key + "Long") ?: 0L
                            if (requestedOutboundId == routeOutbound && currentId == routeOutbound) {
                                loadedOutboundId = routeOutbound
                                outboundName = name
                                notifyChanged()
                            }
                        }
                    }
                }
            }
        }
        return super.getSummary()
    }

}
