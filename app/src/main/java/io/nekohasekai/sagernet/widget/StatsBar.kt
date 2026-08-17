package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenStarted
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomappbar.BottomAppBar
import com.google.android.material.shape.MaterialShapeDrawable
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.utils.ExitLocationResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    private lateinit var statusText: TextView
    private lateinit var exitLocationText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private lateinit var behavior: YourBehavior
    private var exitLookupJob: Job? = null
    private var exitLookupProfileId: Long? = null
    private var lastExitLabel: String? = null
    private var lastConnectionError: String? = null

    var allowShow = true

    init {
        post {
            elevation = dp2px(10).toFloat()
            (background as? MaterialShapeDrawable)?.setStroke(
                dp2px(1).toFloat(),
                context.getColour(R.color.cyber_stroke)
            )
        }
    }

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior { allowShow }
        return behavior
    }

    fun showForPrimaryScreen() {
        allowShow = true
        hideOnScroll = false
        post {
            if (allowShow && isAttachedToWindow) performShow()
        }
    }

    class YourBehavior(val getAllowShow: () -> Boolean) : Behavior() {

        override fun onNestedScroll(
            coordinatorLayout: CoordinatorLayout, child: BottomAppBar, target: View,
            dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
            type: Int, consumed: IntArray,
        ) {
            super.onNestedScroll(
                coordinatorLayout,
                child,
                target,
                dxConsumed,
                dyConsumed + dyUnconsumed,
                dxUnconsumed,
                0,
                type,
                consumed
            )
        }

        override fun slideUp(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideUp(child)
        }

        override fun slideDown(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideDown(child)
        }
    }


    override fun setOnClickListener(l: OnClickListener?) {
        statusText = findViewById(R.id.status)
        exitLocationText = findViewById(R.id.exit_location)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        exitLocationText.setOnClickListener {
            val label = lastExitLabel ?: return@setOnClickListener
            val success = SagerNet.trySetPrimaryClip(label)
            mainActivity().snackbar(
                if (success) R.string.exit_location_copied else R.string.copy_failed
            ).show()
        }
        exitLocationText.setOnLongClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            refreshExitLocation(mainActivity(), DataStore.serviceState.connected, force = true)
            mainActivity().snackbar(R.string.exit_location_refreshing).show()
            true
        }
        TooltipCompat.setTooltipText(
            exitLocationText,
            context.getText(R.string.exit_location_interaction_hint)
        )
        super.setOnClickListener { view ->
            val error = lastConnectionError
            if (error != null) {
                MaterialAlertDialogBuilder(mainActivity())
                    .setTitle(R.string.connection_error_title)
                    .setMessage(error)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } else {
                l?.onClick(view)
            }
        }
    }

    private fun setStatus(
        text: CharSequence,
        tooltip: CharSequence = text,
        colorRes: Int = R.color.cyber_text_secondary,
    ) {
        statusText.text = text
        statusText.setTextColor(context.getColour(colorRes))
        contentDescription = tooltip
        TooltipCompat.setTooltipText(this, tooltip)
    }

    private fun mainActivity(): MainActivity {
        var current: Context = context
        while (current is android.content.ContextWrapper && current !is android.app.Activity) {
            current = current.baseContext
        }
        return current as MainActivity
    }

    fun changeState(state: BaseService.State, errorMessage: String? = null) {
        val activity = mainActivity()
        val incomingError = errorMessage?.takeIf(String::isNotBlank)
        when {
            incomingError != null -> lastConnectionError = incomingError
            state != BaseService.State.Idle -> lastConnectionError = null
        }
        @Suppress("DEPRECATION")
        fun postWhenStarted(what: () -> Unit) = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(100L)
            activity.whenStarted { what() }
        }
        val connected = state == BaseService.State.Connected
        hideOnScroll = false
        postWhenStarted {
            if (allowShow) performShow()
            val error = lastConnectionError
            if (error != null) {
                setStatus(
                    context.getString(R.string.connection_error_inline, error),
                    context.getString(R.string.connection_error_tap_details, error),
                    R.color.cyber_coral_text,
                )
            } else if (state == BaseService.State.Connected) {
                setStatus(
                    context.getText(R.string.vpn_connected_short),
                    context.getText(R.string.vpn_connected)
                )
            } else {
                setStatus(
                    context.getText(
                        when (state) {
                            BaseService.State.Connecting -> R.string.connecting
                            BaseService.State.Stopping -> R.string.stopping
                            else -> R.string.not_connected
                        }
                    )
                )
            }
        }
        if (!connected) {
            updateSpeed(0, 0)
        }
        refreshExitLocation(activity, connected)
    }

    private fun refreshExitLocation(
        activity: MainActivity,
        connected: Boolean,
        force: Boolean = false,
    ) {
        if (!connected) {
            exitLookupJob?.cancel()
            exitLookupJob = null
            exitLookupProfileId = null
            lastExitLabel = null
            exitLocationText.isInvisible = true
            exitLocationText.text = ""
            return
        }

        val profileId = DataStore.currentProfile
        if (!force && exitLookupProfileId == profileId &&
            (exitLookupJob?.isActive == true || exitLocationText.isVisible)
        ) return

        exitLookupJob?.cancel()
        exitLookupProfileId = profileId
        lastExitLabel = null
        exitLocationText.isVisible = true
        exitLocationText.setText(R.string.exit_location_loading)
        exitLookupJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            delay(500L)
            val label = runCatching {
                ExitLocationResolver.resolve(DataStore.mixedPort)
            }.onFailure {
                Logs.w("Exit location lookup failed: ${it.readableMessage}")
            }.getOrNull()

            onMainDispatcher {
                if (!DataStore.serviceState.connected || exitLookupProfileId != profileId) {
                    return@onMainDispatcher
                }
                if (label.isNullOrBlank()) {
                    exitLocationText.isInvisible = true
                } else {
                    val formatted = context.getString(R.string.exit_location_format, label)
                    lastExitLabel = formatted
                    exitLocationText.text = formatted
                    exitLocationText.contentDescription = context.getString(
                        R.string.exit_location_content_description,
                        label
                    )
                    exitLocationText.isVisible = true
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        txText.text = "▲  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, txRate)
            )
        }"
        rxText.text = "▼  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, rxRate)
            )
        }"
    }

    fun testConnection() {
        val activity = mainActivity()
        isEnabled = false
        setStatus(app.getText(R.string.connection_test_testing))
        runOnDefaultDispatcher {
            try {
                val elapsed = activity.urlTest()
                onMainDispatcher {
                    isEnabled = true
                    setStatus(
                        app.getString(
                            if (DataStore.connectionTestURL.startsWith("https://")) {
                                R.string.connection_test_available
                            } else {
                                R.string.connection_test_available_http
                            }, elapsed
                        )
                    )
                }

            } catch (e: Exception) {
                Logs.w(e.toString())
                onMainDispatcher {
                    isEnabled = true
                    setStatus(app.getText(R.string.connection_test_testing))

                    activity.snackbar(
                        app.getString(
                            R.string.connection_test_error, e.readableMessage
                        )
                    ).show()
                }
            }
        }
    }

}
