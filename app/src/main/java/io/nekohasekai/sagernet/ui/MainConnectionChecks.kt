package io.nekohasekai.sagernet.ui

import android.content.Intent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import java.io.File

internal fun MainActivity.checkRuleAssetsBeforeConnect(onConnect: () -> Unit) {
    if (DataStore.skipRuleAssetsCheck) {
        onConnect()
        return
    }
    val assetsDir = SagerNet.application.externalAssets
    val missingAssets = listOf("geoip.db", "geosite.db").filter {
        !File(assetsDir, it).isFile
    }
    if (missingAssets.isEmpty()) {
        onConnect()
        return
    }
    MaterialAlertDialogBuilder(this).setTitle(R.string.route_assets_missing_title)
        .setMessage(
            getString(
                R.string.route_assets_missing_message,
                missingAssets.joinToString(", ")
            )
        )
        .setPositiveButton(R.string.route_assets_missing_download) { _, _ ->
            startActivity(Intent(this, AssetsActivity::class.java))
        }
        .setNegativeButton(R.string.route_assets_missing_connect_anyway) { _, _ ->
            DataStore.skipRuleAssetsCheck = true
            onConnect()
        }
        .setNeutralButton(android.R.string.cancel, null)
        .show()
}
