package io.nekohasekai.sagernet

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

const val ACTION_QUICK_TOGGLE = "io.nekohasekai.sagernet.action.QUICK_TOGGLE"
const val ACTION_QUICK_ENABLE = "io.nekohasekai.sagernet.action.QUICK_ENABLE"
const val ACTION_QUICK_DISABLE = "io.nekohasekai.sagernet.action.QUICK_DISABLE"

internal fun Activity.authorizeShortcutLaunch(expectedAction: String, onAuthorized: () -> Unit) {
    if (intent.action != expectedAction) {
        finish()
        return
    }
    if (isTrustedShortcutCaller()) {
        onAuthorized()
        return
    }
    AlertDialog.Builder(this)
        .setTitle(R.string.app_name)
        .setMessage(R.string.shortcut_control_confirmation)
        .setPositiveButton(android.R.string.ok) { _, _ -> onAuthorized() }
        .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
        .setOnCancelListener { finish() }
        .show()
}

private fun Activity.isTrustedShortcutCaller(): Boolean {
    if (Build.VERSION.SDK_INT < 34) return false
    val callerUid = runCatching { launchedFromUid }.getOrDefault(Process.INVALID_UID)
    if (callerUid == Process.myUid() || callerUid == Process.SYSTEM_UID) return true
    if (callerUid == Process.INVALID_UID) return false

    val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val defaultHome = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
    return defaultHome?.activityInfo?.applicationInfo?.uid == callerUid
}
