package com.yokhan.gates.app.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import com.yokhan.gates.shared.ui.route.YokhanButtonGrid
import com.yokhan.gates.app.ui.compose.YokhanComposeRouteFragment
import com.yokhan.gates.shared.ui.route.YokhanMonoValue
import com.yokhan.gates.shared.ui.route.YokhanRouteAction
import com.yokhan.gates.shared.ui.route.YokhanRouteCard
import com.yokhan.gates.shared.ui.route.YokhanRouteSurface
import com.yokhan.gates.shared.ui.route.yokhanOutlinedSurface
import com.yokhan.gates.shared.ui.design.YokhanSpacing
import moe.matsuri.nb4a.utils.WebViewUtil

class WebviewFragment : YokhanComposeRouteFragment() {

    private val panelUrl = mutableStateOf(normalizePanelUrl(DataStore.yacdURL))
    private val webViewOpen = mutableStateOf(true)
    private var webView: WebView? = null

    private fun normalizePanelUrl(url: String): String {
        return when {
            url.startsWith("http://127.0.0.1") -> url.replaceFirst("http://127.0.0.1", "http://localhost")
            url.startsWith("https://127.0.0.1") -> url.replaceFirst("https://127.0.0.1", "https://localhost")
            else -> url
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val normalized = normalizePanelUrl(DataStore.yacdURL)
        if (normalized != DataStore.yacdURL) {
            DataStore.yacdURL = normalized
        }
        panelUrl.value = normalized
    }

    override fun onDestroyView() {
        destroyWebView()
        super.onDestroyView()
    }

    @Composable
    override fun Content(spaceActive: Boolean) {
        YokhanRouteSurface(
            title = stringResource(R.string.menu_dashboard),
            systemLabel = stringResource(R.string.dashboard_system_label),
            spaceActive = spaceActive,
            onMenuClick = { (activity as? MainActivity)?.openNavigationMenu() },
        ) { colors, typography ->
            YokhanRouteCard(
                title = stringResource(R.string.menu_dashboard),
                colors = colors,
                typography = typography,
                modifier = Modifier.padding(
                    horizontal = YokhanSpacing.ScreenHorizontal,
                    vertical = YokhanSpacing.ScreenTop,
                ),
            ) {
                YokhanButtonGrid(
                    actions = listOf(
                        YokhanRouteAction(
                            title = stringResource(R.string.set_panel_url),
                            primary = true,
                            onClick = ::showSetUrlDialog,
                        ),
                        YokhanRouteAction(
                            title = stringResource(R.string.mal_close),
                            onClick = ::closePanel,
                        ),
                    ),
                    colors = colors,
                    typography = typography,
                )
            }

            if (webViewOpen.value) {
                AndroidView(
                    factory = { context ->
                        createWebView(context).also {
                            webView = it
                            it.loadUrl(panelUrl.value)
                        }
                    },
                    update = {
                        if (it.url != panelUrl.value) {
                            it.loadUrl(panelUrl.value)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = YokhanSpacing.ScreenHorizontal)
                        .padding(bottom = YokhanSpacing.ScreenHorizontal)
                        .yokhanOutlinedSurface(colors),
                )

                DisposableEffect(Unit) {
                    onDispose {
                        destroyWebView()
                    }
                }
            } else {
                YokhanRouteCard(
                    title = stringResource(R.string.menu_dashboard),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier
                        .padding(horizontal = YokhanSpacing.ScreenHorizontal)
                        .weight(1f),
                ) {
                    YokhanMonoValue(
                        text = stringResource(R.string.mal_close),
                        colors = colors,
                        typography = typography,
                    )
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: android.content.Context): WebView {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        return WebView(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.domStorageEnabled = true
            settings.javaScriptEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    WebViewUtil.onReceivedError(view, request, error)
                }
            }
        }
    }

    private fun showSetUrlDialog() {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(DataStore.yacdURL)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.set_panel_url)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val normalized = normalizePanelUrl(input.text.toString())
                DataStore.yacdURL = normalized
                panelUrl.value = normalized
                webViewOpen.value = true
                webView?.loadUrl(normalized)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun destroyWebView() {
        webView?.onPause()
        webView?.removeAllViews()
        webView?.destroy()
        webView = null
    }

    private fun closePanel() {
        destroyWebView()
        webViewOpen.value = false
    }
}
