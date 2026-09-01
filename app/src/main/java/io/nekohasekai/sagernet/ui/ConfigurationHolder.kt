package io.nekohasekai.sagernet.ui

import android.text.format.Formatter
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.QRCodeDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor

        class ConfigurationHolder(val view: View, private val gf: ProfileGroupFragment) :
    RecyclerView.ViewHolder(view),
            PopupMenu.OnMenuItemClickListener {

            lateinit var entity: ProxyEntity
            private var actionAnchor: View? = null

            val profileName: TextView = view.findViewById(R.id.profile_name)
            val profileType: TextView = view.findViewById(R.id.profile_type)
            val profileAddress: TextView = view.findViewById(R.id.profile_address)
            val profileStatus: TextView = view.findViewById(R.id.profile_status)
            val favoriteIndicator: ImageView = view.findViewById(R.id.favorite_indicator)

            val trafficText: TextView = view.findViewById(R.id.traffic_text)
            val chainDetailsContainer: LinearLayout = view.findViewById(R.id.chain_details_container)
            val chainDetails: TextView = view.findViewById(R.id.chain_details)
            val selectedView: View = view.findViewById(R.id.selected_view)
            val editButton: ImageView = view.findViewById(R.id.edit)
            val shareLayout: LinearLayout = view.findViewById(R.id.share)
            val shareLayer: LinearLayout = view.findViewById(R.id.share_layer)
            val shareButton: ImageView = view.findViewById(R.id.shareIcon)
            val removeButton: ImageView = view.findViewById(R.id.remove)

            fun updateSelected(proxyEntity: ProxyEntity) {
                val card = view as? MaterialCardView
                val selected = (gf.selectedItem?.id ?: DataStore.selectedProxy) == proxyEntity.id
                val batchSelected = (gf.parentFragment as? ConfigurationFragment)
                    ?.isBatchSelected(proxyEntity.id) == true
                val started =
                    selected && DataStore.serviceState.started && DataStore.currentProfile == proxyEntity.id
                editButton.isEnabled = !started
                removeButton.isEnabled = !started
                selectedView.visibility = if (selected) View.VISIBLE else View.INVISIBLE
                card?.strokeColor = view.context.getColour(
                    when {
                        batchSelected -> R.color.cyber_cyan_text
                        selected -> R.color.cyber_emerald_text
                        else -> R.color.cyber_stroke
                    }
                )
                card?.strokeWidth = if (selected || batchSelected) dp2px(2) else dp2px(1)
                card?.setCardBackgroundColor(
                    view.context.getColour(
                        if (selected || batchSelected) R.color.cyber_surface_selected else R.color.cyber_surface
                    )
                )
            }

            fun updateFavorite(proxyEntity: ProxyEntity) {
                favoriteIndicator.isVisible = gf.adapter?.isFavorite(proxyEntity.id) == true
            }

            fun updateTraffic(proxyEntity: ProxyEntity, trafficData: TrafficData? = null) {
                val pf = gf.parentFragment as? ConfigurationFragment ?: return
                var rx = proxyEntity.rx
                var tx = proxyEntity.tx
                if (trafficData != null) {
                    tx = trafficData.tx
                    rx = trafficData.rx
                }

                val showTraffic = rx + tx != 0L
                trafficText.isVisible = showTraffic
                if (showTraffic) {
                    trafficText.text = gf.getString(
                        R.string.traffic,
                        Formatter.formatFileSize(view.context, tx),
                        Formatter.formatFileSize(view.context, rx)
                    )
                }

                var address = proxyEntity.displayAddress()
                if (showTraffic && address.length >= 30) {
                    address = address.substring(0, 27) + "..."
                }

                if (proxyEntity.requireBean().name.isBlank() || !pf.alwaysShowAddress) {
                    address = ""
                }

                profileAddress.text = address
                (trafficText.parent as View).isGone =
                    (!showTraffic || proxyEntity.status <= 0) && address.isBlank()

                if (proxyEntity.status <= 0) {
                    if (showTraffic) {
                        profileStatus.text = trafficText.text
                        profileStatus.setTextColor(view.context.getColorAttr(android.R.attr.textColorSecondary))
                        trafficText.text = ""
                    } else {
                        profileStatus.text = ""
                    }
                    profileStatus.background = null
                }
            }

            fun updateStatus(proxyEntity: ProxyEntity) {
                val pf = gf.parentFragment as? ConfigurationFragment ?: return
                if (proxyEntity.id in pf.testingProfileIds) {
                    profileStatus.setText(R.string.connection_test_testing)
                    profileStatus.setTextColor(view.context.getColour(R.color.cyber_cyan_text))
                    profileStatus.background = DrawableCompat.wrap(
                        AppCompatResources.getDrawable(view.context, R.drawable.bg_latency_badge)!!.mutate()
                    ).apply {
                        DrawableCompat.setTint(this, view.context.getColour(R.color.color_cyber_badge_cyan_bg))
                    }
                    profileStatus.setOnClickListener(null)
                    return
                }
                if (proxyEntity.status <= 0) {
                    updateTraffic(proxyEntity)
                    return
                } else if (proxyEntity.status == 1) {
                    val now = System.currentTimeMillis() / 1000L
                    val testedAt = ProfileUiState.lastTestAt(proxyEntity.id)
                    val age = testedAt?.let { (now - it).coerceAtLeast(0L) }
                    val expired = age == null || age > ProfileUiState.TEST_RESULT_EXPIRES_AFTER_SECONDS
                    val ageLabel = when {
                        expired -> gf.getString(R.string.test_result_expired)
                        age < 60L -> gf.getString(R.string.test_result_just_now)
                        age < 3600L -> gf.getString(R.string.test_result_minutes_short, age / 60L)
                        age < 86400L -> gf.getString(R.string.test_result_hours_short, age / 3600L)
                        else -> gf.getString(R.string.test_result_days_short, age / 86400L)
                    }
                    val methodLabel = when (ProfileUiState.lastTestMethod(proxyEntity.id)) {
                        ProfileTestMethod.TCP -> gf.getString(R.string.test_method_tcp)
                        ProfileTestMethod.URL -> gf.getString(R.string.test_method_url)
                        ProfileTestMethod.UNKNOWN -> null
                    }
                    profileStatus.text = if (methodLabel == null) {
                        "● ${proxyEntity.ping} ms · $ageLabel"
                    } else {
                        "$methodLabel ${proxyEntity.ping} ms · $ageLabel"
                    }
                    val (textColor, bgColor) = when {
                        expired -> R.color.cyber_text_secondary to R.color.cyber_stroke_soft
                        proxyEntity.ping <= 100 -> R.color.cyber_emerald_text to R.color.color_cyber_badge_emerald_bg
                        proxyEntity.ping <= 300 -> R.color.cyber_amber_text to R.color.color_cyber_badge_amber_bg
                        else -> R.color.cyber_coral_text to R.color.color_cyber_badge_coral_bg
                    }
                    profileStatus.setTextColor(view.context.getColour(textColor))
                    profileStatus.background = DrawableCompat.wrap(
                        AppCompatResources.getDrawable(view.context, R.drawable.bg_latency_badge)!!.mutate()
                    ).apply {
                        DrawableCompat.setTint(this, view.context.getColour(bgColor))
                    }
                } else {
                    profileStatus.setTextColor(view.context.getColour(R.color.cyber_coral_text))
                    profileStatus.background = DrawableCompat.wrap(
                        AppCompatResources.getDrawable(view.context, R.drawable.bg_latency_badge)!!.mutate()
                    ).apply {
                        DrawableCompat.setTint(this, view.context.getColour(R.color.color_cyber_badge_coral_bg))
                    }
                    if (proxyEntity.status == 2) {
                        profileStatus.text = "● " + (proxyEntity.error ?: "")
                    }
                }

                if (proxyEntity.status == 3) {
                    val err = proxyEntity.error ?: gf.getString(R.string.error_placeholder)
                    val msg = Protocols.genFriendlyMsg(err)
                    profileStatus.text = "● " + (if (msg != err) msg else gf.getString(R.string.unavailable))
                    profileStatus.setOnClickListener {
                        gf.alert(err).tryToShow()
                    }
                } else {
                    profileStatus.setOnClickListener(null)
                }
            }

            fun bind(proxyEntity: ProxyEntity, trafficData: TrafficData? = null) {
                val pf = gf.parentFragment as? ConfigurationFragment ?: return

                entity = proxyEntity

                if (gf.select) {
                    view.setOnClickListener {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        (gf.requireActivity() as ConfigurationFragment.SelectCallback).returnProfile(proxyEntity.id)
                    }
                } else {
                    view.setOnClickListener {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        if (pf.isBatchSelecting()) {
                            pf.toggleBatchSelection(proxyEntity.id)
                            return@setOnClickListener
                        }
                        runOnDefaultDispatcher {
                            if (proxyEntity.type == ProxyEntity.TYPE_CHAIN) {
                                pf.expandedChainIds.add(proxyEntity.id)
                                pf.testChainHops(proxyEntity)
                            }

                            var update: Boolean
                            var lastSelected: Long
                            gf.profileAccess.withLock {
                                update = DataStore.selectedProxy != proxyEntity.id
                                lastSelected = DataStore.selectedProxy
                                DataStore.selectedProxy = proxyEntity.id
                                ProfileUiState.markRecent(proxyEntity.id)
                                onMainDispatcher {
                                    updateSelected(proxyEntity)
                                    pf.refreshRecentUi()
                                }
                            }

                            if (update) {
                                ProfileManager.postUpdate(lastSelected)
                                if (DataStore.serviceState.canStop && gf.reloadAccess.tryLock()) {
                                    SagerNet.reloadService()
                                    gf.reloadAccess.unlock()
                                }
                            } else if (SagerNet.isTv) {
                                if (DataStore.serviceState.started) {
                                    SagerNet.stopService()
                                } else {
                                    SagerNet.startService()
                                }
                            }
                        }

                    }
                }

                profileName.text = proxyEntity.displayName()
                profileType.text = proxyEntity.displayType()
                profileType.setTextColor(view.context.getProtocolColor(proxyEntity.type))
                updateFavorite(proxyEntity)

                updateTraffic(proxyEntity, trafficData)
                updateStatus(proxyEntity)

                val showChainDetails = proxyEntity.type == ProxyEntity.TYPE_CHAIN &&
                        (pf.expandedChainIds.contains(proxyEntity.id) ||
                                (gf.selectedItem?.id ?: DataStore.selectedProxy) == proxyEntity.id)
                chainDetailsContainer.isVisible = showChainDetails
                if (showChainDetails) {
                    chainDetails.text = pf.chainHopDetails[proxyEntity.id]
                        ?: gf.getString(R.string.connection_test)
                } else {
                    chainDetails.text = ""
                }

                editButton.setOnClickListener {
                    it.context.startActivity(
                        proxyEntity.settingIntent(
                            it.context, gf.proxyGroup.type == GroupType.SUBSCRIPTION
                        )
                    )
                }

                removeButton.setOnClickListener {
                    gf.adapter?.let {
                        val index = it.configurationIdList.indexOf(proxyEntity.id)
                        it.remove(index)
                        gf.undoManager.remove(index to proxyEntity)
                    }
                }

                shareLayout.isGone = gf.select
                editButton.isGone = true
                removeButton.isGone = true

                updateSelected(proxyEntity)
                if (!gf.select) {
                    shareLayer.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    shareButton.setImageResource(R.drawable.ic_baseline_more_vert_24)
                    shareButton.isVisible = true
                    shareLayout.setOnClickListener { showCardMenu(it) }
                    view.setOnLongClickListener {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        pf.beginBatchSelection(proxyEntity.id)
                        true
                    }
                } else {
                    view.setOnLongClickListener(null)
                }

            }

            var currentName = ""

            private fun showCardMenu(anchor: View) {
                actionAnchor = anchor
                val popup = PopupMenu(view.context, anchor)
                popup.menuInflater.inflate(R.menu.profile_card_menu, popup.menu)
                val favorite = popup.menu.findItem(R.id.action_toggle_favorite)
                val isFavorite = gf.adapter?.isFavorite(entity.id) == true
                favorite.title = view.context.getString(
                    if (isFavorite) R.string.remove_favorite else R.string.add_favorite
                )
                favorite.setIcon(
                    if (isFavorite) R.drawable.ic_star_filled_24 else R.drawable.ic_star_outline_24
                )
                popup.menu.findItem(R.id.action_remove_recent).isVisible =
                    gf.adapter?.isRecent(entity.id) == true
                val serviceRunningForEntity = isServiceRunningForEntity()
                popup.menu.findItem(R.id.action_edit_profile).isEnabled = !serviceRunningForEntity
                popup.menu.findItem(R.id.action_delete_profile).isEnabled = !serviceRunningForEntity
                popup.setForceShowIcon(true)
                popup.setOnMenuItemClickListener(this@ConfigurationHolder)
                popup.show()
            }

            private fun isServiceRunningForEntity(): Boolean {
                return (gf.selectedItem?.id ?: DataStore.selectedProxy) == entity.id &&
                        DataStore.serviceState.started && DataStore.currentProfile == entity.id
            }

            private fun showShare(anchor: View) {
                val popup = PopupMenu(view.context, anchor)
                popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)
                when {
                    !entity.haveStandardLink() -> {
                        popup.menu.findItem(R.id.action_group_qr).subMenu?.removeItem(R.id.action_standard_qr)
                        popup.menu.findItem(R.id.action_group_clipboard).subMenu?.removeItem(
                            R.id.action_standard_clipboard
                        )
                    }
                    !entity.haveLink() -> {
                        popup.menu.removeItem(R.id.action_group_qr)
                        popup.menu.removeItem(R.id.action_group_clipboard)
                    }
                }
                if (entity.nekoBean != null) popup.menu.removeItem(R.id.action_group_configuration)
                popup.setForceShowIcon(true)
                popup.setOnMenuItemClickListener(this@ConfigurationHolder)
                popup.show()
            }

            fun showCode(link: String) {
                QRCodeDialog(link, currentName).showAllowingStateLoss(gf.parentFragmentManager)
            }

            fun export(link: String) {
                val success = SagerNet.trySetPrimaryClip(link)
                (gf.activity as MainActivity).snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                    .show()
            }

            private fun exportConfig(toFile: Boolean) {
                val target = entity
                gf.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val config = target.exportConfig()
                        withContext(Dispatchers.Main.immediate) {
                            if (toFile) {
                                DataStore.serverConfig = config.first
                                gf.startFilesForResult(
                                    (gf.parentFragment as ConfigurationFragment).exportConfig,
                                    config.second,
                                )
                            } else {
                                export(config.first)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logs.w(e)
                        withContext(Dispatchers.Main.immediate) {
                            (gf.activity as? MainActivity)?.snackbar(e.readableMessage)?.show()
                        }
                    }
                }
            }

            override fun onMenuItemClick(item: MenuItem): Boolean {
                try {
                    currentName = entity.displayName()!!
                    when (item.itemId) {
                        R.id.action_toggle_favorite -> {
                            val favorite = ProfileUiState.toggleFavorite(entity.id)
                            gf.adapter?.refreshUiState(entity.id)
                            (gf.activity as? MainActivity)?.snackbar(
                                if (favorite) R.string.favorite_added else R.string.favorite_removed
                            )?.show()
                        }
                        R.id.action_remove_recent -> {
                            (gf.parentFragment as? ConfigurationFragment)
                                ?.removeRecentProfile(entity.id)
                        }
                        R.id.action_edit_profile -> {
                            if (isServiceRunningForEntity()) return true
                            view.context.startActivity(
                                entity.settingIntent(
                                    view.context, gf.proxyGroup.type == GroupType.SUBSCRIPTION
                                )
                            )
                        }
                        R.id.action_share_profile -> showShare(actionAnchor ?: view)
                        R.id.action_delete_profile -> {
                            if (isServiceRunningForEntity()) return true
                            gf.adapter?.let { adapter ->
                                val index = adapter.configurationIdList.indexOf(entity.id)
                                if (index >= 0) {
                                    adapter.remove(index)
                                    gf.undoManager.remove(index to entity)
                                }
                            }
                        }
                        R.id.action_standard_qr -> showCode(entity.toStdLink())
                        R.id.action_standard_clipboard -> export(entity.toStdLink())
                        R.id.action_universal_qr -> showCode(entity.requireBean().toUniversalLink())
                        R.id.action_universal_clipboard -> export(
                            entity.requireBean().toUniversalLink()
                        )

                        R.id.action_config_export_clipboard -> exportConfig(toFile = false)
                        R.id.action_config_export_file -> exportConfig(toFile = true)
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    (gf.activity as MainActivity).snackbar(e.readableMessage).show()
                    return true
                }
                return true
            }
        }
