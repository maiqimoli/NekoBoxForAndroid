package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.view.*
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.databinding.LayoutGroupItemBinding
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.toBytesString
import java.lang.NumberFormatException
import java.util.*

class GroupHolder(private val owner: GroupFragment, binding: LayoutGroupItemBinding) :
    RecyclerView.ViewHolder(binding.root),
    PopupMenu.OnMenuItemClickListener {

    lateinit var proxyGroup: ProxyGroup
    val groupName = binding.groupName
    val groupStatus = binding.groupStatus
    val groupTraffic = binding.groupTraffic
    val groupUser = binding.groupUser
    val editButton = binding.edit
    val optionsButton = binding.options
    val updateButton = binding.groupUpdate
    val subscriptionUpdateProgress = binding.subscriptionUpdateProgress

    override fun onMenuItemClick(item: MenuItem): Boolean {

        fun export(link: String) {
            val success = SagerNet.trySetPrimaryClip(link)
            owner.activity.snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                .show()
        }

        when (item.itemId) {
            R.id.action_universal_qr -> {
                QRCodeDialog(
                    proxyGroup.toUniversalLink(), proxyGroup.displayName()
                ).showAllowingStateLoss(owner.parentFragmentManager)
            }

            R.id.action_universal_clipboard -> {
                export(proxyGroup.toUniversalLink())
            }

            R.id.action_export_clipboard -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(owner.selectedGroup.id)
                    val links = profiles.joinToString("\n") { it.toStdLink(compact = true) }
                    onMainDispatcher {
                        SagerNet.trySetPrimaryClip(links)
                        owner.snackbar(owner.getString(R.string.copy_toast_msg)).show()
                    }
                }
            }

            R.id.action_export_file -> {
                owner.startFilesForResult(owner.exportProfiles, "profiles_${proxyGroup.displayName()}.txt")
            }

            R.id.action_clear -> {
                MaterialAlertDialogBuilder(owner.requireContext()).setTitle(R.string.confirm)
                    .setMessage(R.string.clear_profiles_message)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        runOnDefaultDispatcher {
                            GroupManager.clearGroup(proxyGroup.id)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        return true
    }


    fun bind(group: ProxyGroup) {
        proxyGroup = group

        itemView.setOnClickListener { }

        editButton.isGone = proxyGroup.ungrouped
        updateButton.isInvisible = proxyGroup.type != GroupType.SUBSCRIPTION
        groupName.text = proxyGroup.displayName()

        editButton.setOnClickListener {
            owner.startActivity(Intent(it.context, GroupSettingsActivity::class.java).apply {
                putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
            })
        }

        updateButton.setOnClickListener {
            GroupUpdater.startUpdate(proxyGroup, true)
        }

        optionsButton.setOnClickListener {
            owner.selectedGroup = proxyGroup

            val popup = PopupMenu(owner.requireContext(), it)
            popup.menuInflater.inflate(R.menu.group_action_menu, popup.menu)

            if (proxyGroup.type != GroupType.SUBSCRIPTION) {
                popup.menu.removeItem(R.id.action_share_subscription)
            }
            popup.setOnMenuItemClickListener(this)
            popup.show()
        }

        if (proxyGroup.id in GroupUpdater.updating) {
            (groupName.parent as LinearLayout).apply {
                setPadding(paddingLeft, dp2px(11), paddingRight, paddingBottom)
            }

            subscriptionUpdateProgress.isVisible = true

            if (!GroupUpdater.progress.containsKey(proxyGroup.id)) {
                subscriptionUpdateProgress.isIndeterminate = true
            } else {
                subscriptionUpdateProgress.isIndeterminate = false
                GroupUpdater.progress[proxyGroup.id]?.let {
                    subscriptionUpdateProgress.max = it.max
                    subscriptionUpdateProgress.progress = it.progress
                }
            }

            updateButton.isInvisible = true
            editButton.isGone = true
        } else {
            (groupName.parent as LinearLayout).apply {
                setPadding(paddingLeft, dp2px(15), paddingRight, paddingBottom)
            }

            subscriptionUpdateProgress.isVisible = false
            updateButton.isInvisible = proxyGroup.type != GroupType.SUBSCRIPTION
            editButton.isGone = proxyGroup.ungrouped
        }

        val subscription = proxyGroup.subscription
        if (subscription != null && subscription.bytesUsed > 0L) { // SIP008 & Open Online Config
            groupTraffic.isVisible = true
            groupTraffic.text = if (subscription.bytesRemaining > 0L) {
                app.getString(
                    R.string.subscription_traffic, Formatter.formatFileSize(
                        app, subscription.bytesUsed
                    ), Formatter.formatFileSize(
                        app, subscription.bytesRemaining
                    )
                )
            } else {
                app.getString(
                    R.string.subscription_used, Formatter.formatFileSize(
                        app, subscription.bytesUsed
                    )
                )
            }
            groupStatus.setPadding(0)
        } else if (subscription != null && !subscription.subscriptionUserinfo.isNullOrBlank()) { // Raw
            var text = ""

            fun get(regex: String): String? {
                return regex.toRegex().findAll(subscription.subscriptionUserinfo).mapNotNull {
                    if (it.groupValues.size > 1) it.groupValues[1] else null
                }.firstOrNull()
            }

            try {
                var used: Long = 0
                get("upload=([0-9]+)")?.apply {
                    used += toLong()
                }
                get("download=([0-9]+)")?.apply {
                    used += toLong()
                }
                val total = get("total=([0-9]+)")?.toLong() ?: 0
                val remain = total - used
                if (used > 0 || total > 0) {
                    text += if (remain > 0) {
                        owner.getString(
                            R.string.subscription_traffic,
                            used.toBytesString(),
                            remain.toBytesString()
                        )
                    } else {
                        owner.getString(R.string.subscription_used, used.toBytesString())
                    }
                }
                get("expire=([0-9]+)")?.apply {
                    text += "\n"
                    text += owner.getString(
                        R.string.subscription_expire,
                        Util.timeStamp2Text(this.toLong() * 1000)
                    )
                }
            } catch (e: NumberFormatException) {
                Logs.w(e)
                // ignore
            }

            if (text.isNotEmpty()) {
                groupTraffic.isVisible = true
                groupTraffic.text = text
                groupStatus.setPadding(0)
            }
        } else {
            groupTraffic.isVisible = false
            groupStatus.setPadding(0, 0, 0, dp2px(4))
        }

        groupUser.text = subscription?.username ?: ""

        runOnDefaultDispatcher {
            val size = SagerDatabase.proxyDao.countByGroup(group.id)
            onMainDispatcher {
                @Suppress("DEPRECATION") when (group.type) {
                    GroupType.BASIC -> {
                        if (size == 0L) {
                            groupStatus.setText(R.string.group_status_empty)
                        } else {
                            groupStatus.text = owner.getString(R.string.group_status_proxies, size)
                        }
                    }

                    GroupType.SUBSCRIPTION -> {
                        groupStatus.text = if (size == 0L) {
                            owner.getString(R.string.group_status_empty_subscription)
                        } else {
                            val date = Date(group.subscription!!.lastUpdated * 1000L)
                            owner.getString(
                                R.string.group_status_proxies_subscription,
                                size,
                                "${date.month + 1} - ${date.date}"
                            )
                        }

                    }
                }
            }

        }

    }
}
