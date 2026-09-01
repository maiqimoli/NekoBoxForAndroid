package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutWebviewBinding
import moe.matsuri.nb4a.utils.WebViewUtil

// Fragment必须有一个无参public的构造函数，否则在数据恢复的时候，会报crash

class WebviewFragment : ToolbarFragment(R.layout.layout_webview), Toolbar.OnMenuItemClickListener {

    lateinit var mWebView: WebView
    private var webViewDestroyed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // layout
        toolbar.setTitle(R.string.menu_dashboard)
        toolbar.inflateMenu(R.menu.yacd_menu)
        toolbar.setOnMenuItemClickListener(this)

        val binding = LayoutWebviewBinding.bind(view)

        // webview
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webViewDestroyed = false
        mWebView = binding.webview
        mWebView.settings.domStorageEnabled = true
        mWebView.settings.javaScriptEnabled = true
        mWebView.settings.allowFileAccess = false
        mWebView.settings.allowContentAccess = false
        mWebView.settings.javaScriptCanOpenWindowsAutomatically = false
        mWebView.settings.setSupportMultipleWindows(false)
        mWebView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        mWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean = request?.url?.let { !isAllowedDashboardUri(it) } ?: true

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                url?.let { !isAllowedDashboardUri(it.toUri()) } ?: true

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                WebViewUtil.onReceivedError(view, request, error)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
            }
        }
        val dashboardUrl = validDashboardUrl(DataStore.yacdURL) ?: DEFAULT_DASHBOARD_URL
        mWebView.loadUrl(authenticatedDashboardUrl(dashboardUrl))
    }

    @SuppressLint("CheckResult")
    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_set_url -> {
                val view = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    setText(DataStore.yacdURL)
                }
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.set_panel_url)
                    .setView(view)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val url = validDashboardUrl(view.text.toString())
                        if (url == null) {
                            Toast.makeText(
                                requireContext(), R.string.invalid_dashboard_url, Toast.LENGTH_LONG
                            ).show()
                        } else {
                            DataStore.yacdURL = url
                            mWebView.loadUrl(authenticatedDashboardUrl(url))
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            R.id.close -> {
                destroyWebView()
            }
        }
        return true
    }

    override fun onDestroyView() {
        destroyWebView()
        super.onDestroyView()
    }

    private fun destroyWebView() {
        if (webViewDestroyed || !::mWebView.isInitialized) return
        webViewDestroyed = true
        mWebView.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
    }

    private fun validDashboardUrl(value: String): String? {
        val uri = runCatching { value.trim().toUri() }.getOrNull() ?: return null
        return value.trim().takeIf { isAllowedDashboardUri(uri) }
    }

    private fun isAllowedDashboardUri(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "https") return !uri.host.isNullOrBlank()
        if (scheme != "http") return false
        return when (uri.host?.lowercase()) {
            "127.0.0.1", "localhost", "::1" -> true
            else -> false
        }
    }

    private fun authenticatedDashboardUrl(value: String): String {
        val uri = value.toUri()
        val isLocalController = uri.scheme.equals("http", ignoreCase = true) &&
            uri.host?.lowercase() in setOf("127.0.0.1", "localhost", "::1") &&
            (uri.port == -1 || uri.port == 9090)
        if (!isLocalController || !DataStore.enableClashAPI || !DataStore.serviceState.connected) {
            return value
        }
        return uri.buildUpon()
            .appendQueryParameter("secret", DataStore.clashApiSecret())
            .build()
            .toString()
    }

    companion object {
        private const val DEFAULT_DASHBOARD_URL = "http://127.0.0.1:9090/ui"
    }
}
