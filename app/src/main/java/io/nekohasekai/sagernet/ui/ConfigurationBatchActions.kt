package io.nekohasekai.sagernet.ui

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import android.view.MenuItem
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.snackbar

class ConfigurationBatchActions(private val owner: ConfigurationFragment) {
private var batchActionMode: ActionMode? = null
private val batchSelection = LinkedHashSet<Long>()
val isSelecting: Boolean get() = batchActionMode != null

fun isSelected(profileId: Long) = profileId in batchSelection

fun beginBatchSelection(initialProfileId: Long? = null) {
    if (owner.select) return
    val listAdapter = owner.getCurrentProfileGroupFragment()?.adapter
    if (listAdapter == null || listAdapter.configurationIdList.isEmpty()) {
        owner.snackbar(R.string.batch_no_profiles).show()
        return
    }
    if (batchActionMode == null) {
        batchActionMode = (owner.requireActivity() as AppCompatActivity)
            .startSupportActionMode(batchActionModeCallback)
    }
    initialProfileId?.let { toggleBatchSelection(it) }
    updateBatchActionMode()
}

fun toggleBatchSelection(profileId: Long) {
    if (batchActionMode == null) beginBatchSelection()
    if (batchActionMode == null) return
    if (!batchSelection.add(profileId)) batchSelection.remove(profileId)
    owner.getCurrentProfileGroupFragment()?.adapter?.let { listAdapter ->
        val index = listAdapter.configurationIdList.indexOf(profileId)
        if (index >= 0) listAdapter.notifyItemChanged(index, ConfigurationPayload.SELECTED)
    }
    updateBatchActionMode()
}

private fun updateBatchActionMode() {
    batchActionMode?.title = owner.resources.getQuantityString(
        R.plurals.batch_selected,
        batchSelection.size,
        batchSelection.size,
    )
    batchActionMode?.invalidate()
}

fun exit() {
    batchActionMode?.finish()
}

private val batchActionModeCallback = object : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode, menu: android.view.Menu): Boolean {
        mode.menuInflater.inflate(R.menu.batch_profile_menu, menu)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: android.view.Menu): Boolean {
        val hasSelection = batchSelection.isNotEmpty()
        menu.findItem(R.id.action_batch_favorite).isEnabled = hasSelection
        menu.findItem(R.id.action_batch_test).isEnabled = hasSelection
        menu.findItem(R.id.action_batch_delete).isEnabled = hasSelection
        val listAdapter = owner.getCurrentProfileGroupFragment()?.adapter
        menu.findItem(R.id.action_batch_favorite).setTitle(
            if (hasSelection && listAdapter?.areAllFavorites(batchSelection) == true) {
                R.string.batch_remove_favorite
            } else {
                R.string.batch_add_favorite
            }
        )
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val fragment = owner.getCurrentProfileGroupFragment() ?: return false
        val listAdapter = fragment.adapter ?: return false
        return when (item.itemId) {
            R.id.action_batch_select_all -> {
                val visibleIds = listAdapter.configurationIdList.toSet()
                val selectAll = !batchSelection.containsAll(visibleIds)
                val changed = if (selectAll) visibleIds - batchSelection else batchSelection.intersect(visibleIds)
                if (selectAll) batchSelection.addAll(visibleIds) else batchSelection.removeAll(visibleIds)
                changed.forEach { id ->
                    val index = listAdapter.configurationIdList.indexOf(id)
                    if (index >= 0) listAdapter.notifyItemChanged(index, ConfigurationPayload.SELECTED)
                }
                updateBatchActionMode()
                true
            }
            R.id.action_batch_favorite -> {
                val favorite = !listAdapter.areAllFavorites(batchSelection)
                ProfileUiState.setFavorites(batchSelection, favorite)
                listAdapter.refreshUiState()
                owner.snackbar(
                    if (favorite) R.string.batch_favorite_added else R.string.batch_favorite_removed
                ).show()
                mode.finish()
                true
            }
            R.id.action_batch_test -> {
                val selectedIds = batchSelection.toSet()
                mode.finish()
                owner.urlTest(selectedIds)
                true
            }
            R.id.action_batch_delete -> {
                val activeProfile = DataStore.currentProfile
                if (DataStore.serviceState.started && activeProfile in batchSelection) {
                    owner.snackbar(R.string.batch_delete_active_profile).show()
                } else {
                    val ids = batchSelection.toSet()
                    MaterialAlertDialogBuilder(owner.requireContext())
                        .setTitle(R.string.batch_delete_title)
                        .setMessage(owner.resources.getQuantityString(
                            R.plurals.batch_delete_message,
                            ids.size,
                            ids.size,
                        ))
                        .setPositiveButton(R.string.delete) { _, _ ->
                            listAdapter.removeByIds(ids)
                            mode.finish()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                true
            }
            else -> false
        }
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        val selectedIds = batchSelection.toList()
        batchSelection.clear()
        batchActionMode = null
        owner.getCurrentProfileGroupFragment()?.adapter?.let { listAdapter ->
            selectedIds.forEach { id ->
                val index = listAdapter.configurationIdList.indexOf(id)
                if (index >= 0) listAdapter.notifyItemChanged(index, ConfigurationPayload.SELECTED)
            }
        }
    }
}
}
