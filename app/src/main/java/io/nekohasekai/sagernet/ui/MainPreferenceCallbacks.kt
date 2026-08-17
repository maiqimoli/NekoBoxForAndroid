package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.snackbar

internal fun MainActivity.handlePreferenceStoreChange(key: String) {
    when (key) {
        Key.SERVICE_MODE -> onBinderDied()
        Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
            if (DataStore.serviceState.canStop) {
                snackbar(getString(io.nekohasekai.sagernet.R.string.need_reload))
                    .setAction(io.nekohasekai.sagernet.R.string.apply) {
                        SagerNet.reloadService()
                    }.show()
            }
        }
    }
}
