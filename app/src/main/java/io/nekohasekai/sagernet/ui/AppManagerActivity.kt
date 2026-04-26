package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.TextUtils
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.yokhan.vpn.shared.ui.design.YokhanPalette
import com.yokhan.vpn.shared.ui.design.YokhanTypography
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ui.compose.YokhanButtonGrid
import io.nekohasekai.sagernet.ui.compose.YokhanCheckboxRow
import io.nekohasekai.sagernet.ui.compose.YokhanRouteAction
import io.nekohasekai.sagernet.ui.compose.YokhanSelectableApp
import io.nekohasekai.sagernet.ui.compose.YokhanSelectableAppsScreen
import io.nekohasekai.sagernet.ui.compose.YokhanSegmentedActions
import io.nekohasekai.sagernet.utils.PackageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.utils.NGUtil
import kotlin.coroutines.coroutineContext

class AppManagerActivity : ThemedActivity() {

    private enum class ProxyMode {
        Off,
        Proxy,
        Bypass,
    }

    private class ProxiedApp(
        private val pm: PackageManager,
        private val appInfo: ApplicationInfo,
        val packageName: String,
    ) {
        val name: String = appInfo.loadLabel(pm).toString()
        val icon: Drawable get() = appInfo.loadIcon(pm)
        val uid: Int get() = appInfo.uid
        val sys: Boolean get() = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

        fun toUi() = YokhanSelectableApp(
            packageName = packageName,
            label = name,
            uid = uid,
            system = sys,
            icon = icon,
        )
    }

    private var loader: Job? = null
    private var apps = emptyList<ProxiedApp>()
    private val filteredApps = mutableStateOf<List<ProxiedApp>>(emptyList())
    private val selectedUids = mutableStateOf<Set<Int>>(emptySet())
    private val searchText = mutableStateOf("")
    private val showSystemApps = mutableStateOf(true)
    private val bypassRuApps = mutableStateOf(DataStore.enableRuBypass)
    private val proxyMode = mutableStateOf(
        if (!DataStore.proxyApps) ProxyMode.Off else if (DataStore.bypass) ProxyMode.Bypass else ProxyMode.Proxy,
    )
    private val loading = mutableStateOf(true)
    private val allAppsEmpty = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!DataStore.proxyApps) {
            DataStore.proxyApps = true
            proxyMode.value = if (DataStore.bypass) ProxyMode.Bypass else ProxyMode.Proxy
        }
        initProxiedUids()
        setContent {
            Content()
        }
        loadApps()
    }

    @Composable
    private fun Content() {
        YokhanSelectableAppsScreen(
            title = getString(R.string.proxied_apps),
            spaceActive = DataStore.serviceState.isSpaceActive(),
            searchText = searchText.value,
            onSearchTextChange = {
                searchText.value = it
                applyFilter()
            },
            showSystemApps = showSystemApps.value,
            onShowSystemAppsChange = {
                showSystemApps.value = it
                applyFilter()
            },
            loading = loading.value,
            apps = filteredApps.value.map { it.toUi() },
            allAppsEmpty = allAppsEmpty.value,
            isSelected = { it.uid in selectedUids.value },
            onToggleApp = { toggleApp(it.uid) },
            actions = listOf(
                YokhanRouteAction(
                    title = getString(R.string.invert_selections),
                    enabled = apps.isNotEmpty(),
                    onClick = ::invertSelections,
                ),
                YokhanRouteAction(
                    title = getString(R.string.clear_selections),
                    enabled = selectedUids.value.isNotEmpty(),
                    onClick = ::clearSelections,
                ),
                YokhanRouteAction(
                    title = getString(R.string.action_export_clipboard),
                    onClick = ::exportClipboard,
                ),
                YokhanRouteAction(
                    title = getString(R.string.action_import),
                    primary = true,
                    onClick = ::importClipboard,
                ),
            ),
            onOpenSettings = ::openAppSettings,
            onBack = ::finish,
            extraControls = { colors, typography ->
                ManagerControls(colors, typography)
            },
        )
    }

    @Composable
    private fun ColumnScope.ManagerControls(
        colors: YokhanPalette,
        typography: YokhanTypography,
    ) {
        YokhanSegmentedActions(
            actions = listOf(
                YokhanRouteAction(
                    title = stringResource(R.string.off),
                    primary = proxyMode.value == ProxyMode.Off,
                    onClick = { setProxyMode(ProxyMode.Off) },
                ),
                YokhanRouteAction(
                    title = stringResource(R.string.route_proxy),
                    primary = proxyMode.value == ProxyMode.Proxy,
                    onClick = { setProxyMode(ProxyMode.Proxy) },
                ),
                YokhanRouteAction(
                    title = stringResource(R.string.bypass_apps),
                    primary = proxyMode.value == ProxyMode.Bypass,
                    onClick = { setProxyMode(ProxyMode.Bypass) },
                ),
            ),
            colors = colors,
            typography = typography,
        )
        YokhanCheckboxRow(
            title = stringResource(R.string.bypass_ru_apps),
            checked = bypassRuApps.value,
            colors = colors,
            typography = typography,
            onCheckedChange = {
                bypassRuApps.value = it
                DataStore.enableRuBypass = it
                applyRuBypass(it)
            },
            modifier = Modifier.padding(top = 10.dp),
        )
        YokhanButtonGrid(
            actions = listOf(
                YokhanRouteAction(
                    title = stringResource(R.string.auto_select_proxy_apps),
                    onClick = ::selectProxyApp,
                ),
            ),
            colors = colors,
            typography = typography,
            modifier = Modifier.padding(top = 10.dp),
        )
    }

    private fun loadApps() {
        loader?.cancel()
        loader = lifecycleScope.launch {
            loading.value = true
            val loaded = withContext(Dispatchers.IO) {
                PackageCache.reload()
                cachedApps.mapNotNull { (packageName, packageInfo) ->
                    coroutineContext.ensureActive()
                    packageInfo.applicationInfo?.let {
                        ProxiedApp(packageManager, it, packageName)
                    }
                }
            }
            apps = sortApps(loaded)
            allAppsEmpty.value = apps.isEmpty()
            loading.value = false
            applyFilter()
        }
    }

    private fun applyFilter() {
        val query = searchText.value.trim()
        var filtered = if (query.isBlank()) {
            apps
        } else {
            apps.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true) ||
                    it.uid.toString().contains(query)
            }
        }
        if (!showSystemApps.value) {
            filtered = filtered.filter { !it.sys }
        }
        filteredApps.value = filtered
    }

    private fun initProxiedUids(str: String = DataStore.individual) {
        selectedUids.value = packagesToUids(str)
        applyBundledBypassSelections()
    }

    private fun packagesToUids(str: String): Set<Int> {
        val installedApps = cachedApps
        return str.lineSequence()
            .mapNotNull { line -> installedApps[line]?.applicationInfo?.uid }
            .toSet()
    }

    private fun packagesToUids(packages: Set<String>): Set<Int> {
        val installedApps = cachedApps
        return packages
            .mapNotNull { packageName -> installedApps[packageName]?.applicationInfo?.uid }
            .toSet()
    }

    private fun bundledBypassPackages(): Set<String> {
        val packages = mutableSetOf<String>()
        if (DataStore.enableRuBypass) {
            packages.addAll(loadAssetPackageList("ru_bypass_packages.txt"))
        }
        if (DataStore.stealthMode) {
            packages.addAll(loadAssetPackageList("stealth_bypass_packages.txt"))
        }
        return packages
    }

    private fun applyBundledBypassSelections() {
        selectedUids.value = selectedUids.value + packagesToUids(bundledBypassPackages())
    }

    private fun toggleApp(uid: Int) {
        selectedUids.value = selectedUids.value.toggle(uid)
        applyBundledBypassSelections()
        persistSelections()
        applyFilter()
    }

    private fun invertSelections() {
        selectedUids.value = apps
            .map { it.uid }
            .toSet()
            .let { allUids -> allUids - selectedUids.value + (selectedUids.value - allUids) }
        applyBundledBypassSelections()
        apps = sortApps(apps)
        persistSelections()
        applyFilter()
    }

    private fun clearSelections() {
        selectedUids.value = emptySet()
        applyBundledBypassSelections()
        apps = sortApps(apps)
        persistSelections()
        applyFilter()
    }

    private fun persistSelections() {
        val bundledPackages = bundledBypassPackages()
        DataStore.individual = apps
            .filter { it.uid in selectedUids.value && it.packageName !in bundledPackages }
            .joinToString("\n") { it.packageName }
    }

    private fun setProxyMode(mode: ProxyMode) {
        proxyMode.value = mode
        when (mode) {
            ProxyMode.Off -> {
                DataStore.proxyApps = false
                finish()
            }

            ProxyMode.Proxy -> {
                DataStore.proxyApps = true
                DataStore.bypass = false
            }

            ProxyMode.Bypass -> {
                DataStore.proxyApps = true
                DataStore.bypass = true
            }
        }
    }

    private fun exportClipboard() {
        val success = SagerNet.trySetPrimaryClip("${DataStore.bypass}\n${DataStore.individual}")
        showSnackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
    }

    private fun importClipboard() {
        val proxiedAppString = SagerNet.clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        if (!proxiedAppString.isNullOrEmpty()) {
            val i = proxiedAppString.indexOf('\n')
            try {
                val (enabled, importedApps) = if (i < 0) {
                    proxiedAppString to ""
                } else {
                    proxiedAppString.substring(0, i) to proxiedAppString.substring(i + 1)
                }
                setProxyMode(if (enabled.toBoolean()) ProxyMode.Bypass else ProxyMode.Proxy)
                DataStore.individual = importedApps
                initProxiedUids(importedApps)
                showSnackbar(R.string.action_import_msg)
                applyFilter()
                return
            } catch (_: IllegalArgumentException) {
            }
        }
        showSnackbar(R.string.action_import_err)
    }

    private fun applyRuBypass(enable: Boolean) {
        if (apps.isEmpty()) return
        try {
            val ruUids = packagesToUids(loadAssetPackageList("ru_bypass_packages.txt"))
            selectedUids.value = if (enable) {
                selectedUids.value + ruUids
            } else {
                selectedUids.value - ruUids
            }
            applyBundledBypassSelections()
            persistSelections()
            apps = sortApps(apps)
            applyFilter()
        } catch (e: Exception) {
            Logs.e(e)
        }
    }

    private fun selectProxyApp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm)
            .setMessage(R.string.auto_select_proxy_apps_message)
            .setPositiveButton(R.string.yes) { _, _ ->
                try {
                    val needProxyAppsList = getAutoProxyApps("")
                    val bypass = DataStore.bypass
                    val next = mutableSetOf<Int>()
                    for ((packageName, packageInfo) in cachedApps) {
                        val uid = packageInfo.applicationInfo?.uid ?: continue
                        val needProxy = needProxyAppsList.contains(packageName) || uid == 1000
                        if ((!bypass && needProxy) || (bypass && !needProxy)) {
                            next.add(uid)
                        }
                    }
                    selectedUids.value = next
                    applyBundledBypassSelections()
                    persistSelections()
                    apps = sortApps(apps)
                    applyFilter()
                } catch (e: Exception) {
                    Logs.e(e)
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun loadAssetPackageList(assetName: String): Set<String> {
        return try {
            NGUtil.readTextFromAssets(app, assetName)
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun getAutoProxyApps(content: String): List<String> {
        var list = listOf<String>()
        try {
            val proxyApps = if (TextUtils.isEmpty(content)) {
                NGUtil.readTextFromAssets(app, "proxy_packagename.txt")
            } else {
                content
            }
            if (!TextUtils.isEmpty(proxyApps)) {
                list = proxyApps.split("\n")
            }
        } catch (_: Exception) {
        }
        return list
    }

    private fun openAppSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun showSnackbar(message: Int) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show()
    }

    private fun sortApps(source: List<ProxiedApp>): List<ProxiedApp> {
        val selected = selectedUids.value
        return source.sortedWith(compareBy({ it.uid !in selected }, { it.name }))
    }

    private fun Set<Int>.toggle(uid: Int): Set<Int> {
        return if (uid in this) this - uid else this + uid
    }

    override fun supportNavigateUpTo(upIntent: Intent) =
        super.supportNavigateUpTo(upIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))

    override fun onDestroy() {
        loader?.cancel()
        super.onDestroy()
    }

    private fun BaseService.State.isSpaceActive(): Boolean {
        return this == BaseService.State.Connecting ||
            this == BaseService.State.Connected ||
            this == BaseService.State.Stopping
    }

    private companion object {
        private val cachedApps
            get() = PackageCache.installedPackages.toMutableMap().apply {
                remove(BuildConfig.APPLICATION_ID)
            }
    }
}
