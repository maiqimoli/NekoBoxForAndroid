package io.nekohasekai.sagernet.ui

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.snackbar

fun ConfigurationFragment.refreshRecentUi() {
    adapter.groupFragments.values.forEach { it.adapter?.refreshUiState() }
    updateRecentMenuVisibility()
}

fun ConfigurationFragment.updateRecentMenuVisibility() {
    if (!select) {
        toolbar.menu.findItem(R.id.action_clear_recent)?.isVisible =
            ProfileUiState.recentIds().isNotEmpty()
    }
}

fun ConfigurationFragment.updateSubscriptionMenuVisibility() {
    if (select) return
    toolbar.menu.findItem(R.id.action_update_subscription)?.isVisible = true
}

private fun ConfigurationFragment.restoreRecentSnapshot(snapshot: List<Long>) {
    val snapshotIds = snapshot.toSet()
    val newerEntries = ProfileUiState.recentIds().filterNot { it in snapshotIds }
    ProfileUiState.restoreRecent(newerEntries + snapshot)
    refreshRecentUi()
}

fun ConfigurationFragment.removeRecentProfile(profileId: Long) {
    val snapshot = ProfileUiState.recentIds()
    if (!ProfileUiState.removeRecent(profileId)) return
    refreshRecentUi()
    snackbar(R.string.recent_record_removed).setAction(R.string.undo) {
        restoreRecentSnapshot(snapshot)
    }.show()
}

fun ConfigurationFragment.clearRecentProfiles() {
    val snapshot = ProfileUiState.recentIds()
    if (snapshot.isEmpty()) {
        snackbar(R.string.recent_records_empty_title).show()
        updateRecentMenuVisibility()
        return
    }
    MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.clear_recent_records)
        .setMessage(R.string.clear_recent_records_confirm)
        .setPositiveButton(R.string.clear_profiles) { _, _ ->
            ProfileUiState.clearRecent()
            refreshRecentUi()
            snackbar(R.string.recent_records_cleared).setAction(R.string.undo) {
                restoreRecentSnapshot(snapshot)
            }.show()
        }
        .setNegativeButton(R.string.no, null)
        .show()
}
