package io.nekohasekai.sagernet.ui

import android.graphics.Color
import android.text.format.Formatter
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor

        class ConfigurationHolder(val view: View, private val gf: ProfileGroupFragment) :
    RecyclerView.ViewHolder(view),
            PopupMenu.OnMenuItemClickListener {

            lateinit var entity: ProxyEntity

            val profileName: TextView = view.findViewById(R.id.profile_name)
            val profileType: TextView = view.findViewById(R.id.profile_type)
            val profileAddress: TextView = view.findViewById(R.id.profile_address)
            val profileStatus: TextView = view.findViewById(R.id.profile_status)

            val trafficText: TextView = view.findViewById(R.id.traffic_text)
            val chainDetailsContainer: LinearLayout = view.findViewById(R.id.chain_details_container)
            val chainDetails: TextView = view.findViewById(R.id.chain_details)
            val selectedView: LinearLayout = view.findViewById(R.id.selected_view)
            val editButton: ImageView = view.findViewById(R.id.edit)
            val shareLayout: LinearLayout = view.findViewById(R.id.share)
            val shareLayer: LinearLayout = view.findViewById(R.id.share_layer)
            val shareButton: ImageView = view.findViewById(R.id.shareIcon)
            val removeButton: ImageView = view.findViewById(R.id.remove)

            fun updateSelected(proxyEntity: ProxyEntity) {
                val card = view as? MaterialCardView
                val selected = (gf.selectedItem?.id ?: DataStore.selectedProxy) == proxyEntity.id
                val started =
                    selected && DataStore.serviceState.started && DataStore.currentProfile == proxyEntity.id
                editButton.isEnabled = !started
                removeButton.isEnabled = !started
                selectedView.visibility = if (selected) View.VISIBLE else View.INVISIBLE
                card?.strokeColor = if (selected) view.context.getColorAttr(R.attr.colorPrimary) else 0x1F888888.toInt()
                card?.strokeWidth = if (selected) dp2px(2) else dp2px(1)
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
                if (proxyEntity.status <= 0) {
                    updateTraffic(proxyEntity)
                    return
                } else if (proxyEntity.status == 1) {
                    profileStatus.text = gf.getString(R.string.available, proxyEntity.ping)
                    val badgeColor = when {
                        proxyEntity.ping <= 100 -> R.color.material_green_500
                        proxyEntity.ping <= 300 -> R.color.material_orange_500
                        else -> R.color.material_red_500
                    }
                    profileStatus.setTextColor(view.context.getColour(R.color.material_light_white))
                    profileStatus.background = DrawableCompat.wrap(
                        view.context.getDrawable(R.drawable.bg_latency_badge)!!.mutate()
                    ).apply {
                        DrawableCompat.setTint(this, view.context.getColour(badgeColor))
                    }
                } else {
                    profileStatus.setTextColor(view.context.getColour(R.color.material_light_white))
                    profileStatus.background = DrawableCompat.wrap(
                        view.context.getDrawable(R.drawable.bg_latency_badge)!!.mutate()
                    ).apply {
                        DrawableCompat.setTint(this, view.context.getColour(R.color.material_red_500))
                    }
                    if (proxyEntity.status == 2) {
                        profileStatus.text = proxyEntity.error
                    }
                }

                if (proxyEntity.status == 3) {
                    val err = proxyEntity.error ?: gf.getString(R.string.error_placeholder)
                    val msg = Protocols.genFriendlyMsg(err)
                    profileStatus.text = if (msg != err) msg else gf.getString(R.string.unavailable)
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
                                onMainDispatcher {
                                    updateSelected(proxyEntity)
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

                val selectOrChain = gf.select || proxyEntity.type == ProxyEntity.TYPE_CHAIN
                shareLayout.isGone = selectOrChain
                editButton.isGone = gf.select
                removeButton.isGone = gf.select

                proxyEntity.nekoBean?.apply {
                    shareLayout.isGone = true
                }

                runOnDefaultDispatcher {
                    onMainDispatcher {
                        updateSelected(proxyEntity)
                    }

                    fun showShare(anchor: View) {
                        val popup = PopupMenu(view.context, anchor)
                        popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)

                        when {
                            !proxyEntity.haveStandardLink() -> {
                                popup.menu.findItem(R.id.action_group_qr).subMenu?.removeItem(R.id.action_standard_qr)
                                popup.menu.findItem(R.id.action_group_clipboard).subMenu?.removeItem(
                                    R.id.action_standard_clipboard
                                )
                            }

                            !proxyEntity.haveLink() -> {
                                popup.menu.removeItem(R.id.action_group_qr)
                                popup.menu.removeItem(R.id.action_group_clipboard)
                            }
                        }

                        if (proxyEntity.nekoBean != null) {
                            popup.menu.removeItem(R.id.action_group_configuration)
                        }

                        popup.setOnMenuItemClickListener(this@ConfigurationHolder)
                        popup.show()
                    }

                    if (!(gf.select || proxyEntity.type == ProxyEntity.TYPE_CHAIN)) {
                        onMainDispatcher {
                            shareLayer.setBackgroundColor(Color.TRANSPARENT)
                            shareButton.setImageResource(R.drawable.ic_social_share)
                            shareButton.setColorFilter(Color.GRAY)
                            shareButton.isVisible = true

                            shareLayout.setOnClickListener {
                                showShare(it)
                            }
                        }
                    }
                }

            }

            var currentName = ""
            fun showCode(link: String) {
                QRCodeDialog(link, currentName).showAllowingStateLoss(gf.parentFragmentManager)
            }

            fun export(link: String) {
                val success = SagerNet.trySetPrimaryClip(link)
                (gf.activity as MainActivity).snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                    .show()
            }

            override fun onMenuItemClick(item: MenuItem): Boolean {
                try {
                    currentName = entity.displayName()!!
                    when (item.itemId) {
                        R.id.action_standard_qr -> showCode(entity.toStdLink())
                        R.id.action_standard_clipboard -> export(entity.toStdLink())
                        R.id.action_universal_qr -> showCode(entity.requireBean().toUniversalLink())
                        R.id.action_universal_clipboard -> export(
                            entity.requireBean().toUniversalLink()
                        )

                        R.id.action_config_export_clipboard -> export(entity.exportConfig().first)
                        R.id.action_config_export_file -> {
                            val cfg = entity.exportConfig()
                            DataStore.serverConfig = cfg.first
                            gf.startFilesForResult(
                                (gf.parentFragment as ConfigurationFragment).exportConfig, cfg.second
                            )
                        }
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    (gf.activity as MainActivity).snackbar(e.readableMessage).show()
                    return true
                }
                return true
            }
        }
