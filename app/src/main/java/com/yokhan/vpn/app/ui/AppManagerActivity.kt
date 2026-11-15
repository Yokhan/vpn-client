package com.yokhan.gates.app.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.yokhan.gates.app.routing.RuCompatibilityClassifier
import com.yokhan.gates.shared.ui.design.YokhanPalette
import com.yokhan.gates.shared.ui.design.YokhanTypography
import com.yokhan.gates.shared.ui.route.YokhanBody
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import com.yokhan.gates.shared.ui.route.YokhanButtonGrid
import com.yokhan.gates.shared.ui.route.YokhanRouteAction
import com.yokhan.gates.shared.ui.route.YokhanToggleRow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.yokhan.gates.app.ui.compose.YokhanFilterChipItem
import com.yokhan.gates.app.ui.compose.YokhanSelectableApp
import com.yokhan.gates.app.ui.compose.YokhanSelectableAppsScreen
import com.yokhan.gates.app.ui.compose.YokhanSegmentedActions
import java.util.concurrent.ConcurrentHashMap
import io.nekohasekai.sagernet.utils.PackageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.utils.NGUtil
import kotlin.coroutines.coroutineContext

class AppManagerActivity : ThemedActivity() {

    private enum class AppFilterTab(val label: String) {
        All("Все"),
        Selected("Выбранные"),
        Ru("РФ"),
        User("Свои"),
        System("Системные"),
    }

    private class ProxiedApp(
        private val pm: PackageManager,
        private val appInfo: ApplicationInfo,
        val packageName: String,
        val isRuDirect: Boolean,
        iconBitmap: ImageBitmap?,
    ) {
        val name: String = appInfo.loadLabel(pm).toString()
        val uid: Int get() = appInfo.uid
        val sys: Boolean get() = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

        val uiModel: YokhanSelectableApp = YokhanSelectableApp(
            packageName = packageName,
            label = name,
            uid = uid,
            system = sys,
            icon = null,
            iconBitmap = iconBitmap,
        )
    }

    private var loader: Job? = null
    private var filterJob: Job? = null
    private var apps = emptyList<ProxiedApp>()
    private var cachedBundledPackages = emptySet<String>()
    private val filteredUiApps = mutableStateOf<List<YokhanSelectableApp>>(emptyList())
    private val selectedUids = mutableStateOf<Set<Int>>(emptySet())
    private val searchText = mutableStateOf("")
    private val filterTab = mutableStateOf(AppFilterTab.All)
    private val showSystemApps = mutableStateOf(true)
    private val bypassRuApps = mutableStateOf(DataStore.enableRuBypass)
    private val openPlan = planAppManagerOpen(DataStore.proxyApps, DataStore.bypass)
    private val proxyMode = mutableStateOf(openPlan.proxyMode)
    private val pendingReconnect = mutableStateOf(false)
    private val loading = mutableStateOf(true)
    private val allAppsEmpty = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initProxiedUids()
        setContent {
            Content()
        }
        loadApps()
    }

    @Composable
    private fun Content() {
        val currentTab = filterTab.value
        val chipItems = AppFilterTab.entries.map { tab ->
            YokhanFilterChipItem(
                key = tab.name,
                label = tab.label,
                selected = currentTab == tab,
                onClick = {
                    if (filterTab.value != tab) {
                        filterTab.value = tab
                        applyFilter()
                    }
                },
            )
        }
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
            apps = filteredUiApps.value,
            allAppsEmpty = allAppsEmpty.value,
            isSelected = { it.uid in selectedUids.value },
            onToggleApp = { toggleApp(it.uid) },
            filterChips = chipItems,
            actions = listOf(
                YokhanRouteAction(
                    title = getString(R.string.auto_select_proxy_apps),
                    fullWidth = true,
                    onClick = ::selectProxyApp,
                ),
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
                    primary = proxyMode.value == AppManagerProxyMode.Off,
                    onClick = { setProxyMode(AppManagerProxyMode.Off) },
                ),
                YokhanRouteAction(
                    title = stringResource(R.string.route_proxy),
                    primary = proxyMode.value == AppManagerProxyMode.Proxy,
                    onClick = { setProxyMode(AppManagerProxyMode.Proxy) },
                ),
                YokhanRouteAction(
                    title = stringResource(R.string.bypass_apps),
                    primary = proxyMode.value == AppManagerProxyMode.Bypass,
                    onClick = { setProxyMode(AppManagerProxyMode.Bypass) },
                ),
            ),
            colors = colors,
            typography = typography,
        )
        YokhanToggleRow(
            title = stringResource(R.string.settings_ru_bypass_toggle_title),
            summary = stringResource(R.string.settings_ru_bypass_toggle_summary),
            checked = bypassRuApps.value,
            colors = colors,
            typography = typography,
            onCheckedChange = {
                if (bypassRuApps.value != it) pendingReconnect.value = true
                bypassRuApps.value = it
                DataStore.enableRuBypass = it
                if (it && DataStore.wholeTrafficMode) {
                    DataStore.wholeTrafficMode = false
                }
                applyRuBypass(it)
            },
            modifier = Modifier.padding(top = 12.dp),
        )
        if (alwaysOnLockdownEnabled()) {
            YokhanBody(
                text = stringResource(R.string.settings_lockdown_bypass_warning),
                colors = colors,
                typography = typography,
                modifier = Modifier.padding(top = 10.dp),
                maxLines = 4,
            )
        }
        if (pendingReconnect.value) {
            YokhanBody(
                text = "Изменения применятся после переподключения",
                colors = colors,
                typography = typography,
                modifier = Modifier.padding(top = 10.dp).testTag("app-manager.pending-reconnect"),
            )
            if (DataStore.serviceState.canStop) {
                YokhanButtonGrid(
                    actions = listOf(
                        YokhanRouteAction(
                            title = stringResource(R.string.app_manager_apply_reconnect),
                            primary = true,
                            onClick = {
                                SagerNet.reloadService()
                                pendingReconnect.value = false
                                showSnackbar(R.string.app_manager_reconnecting)
                            },
                        ),
                    ),
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    private fun loadApps() {
        loader?.cancel()
        loader = lifecycleScope.launch {
            loading.value = true
            val result = withContext(Dispatchers.IO) {
                PackageCache.reload()
                val snapshot = cachedApps
                val packageNames = snapshot.keys.toList()
                val installers = RuCompatibilityClassifier.resolveInstallers(packageManager, packageNames)
                val ruDirectSet = RuCompatibilityClassifier(this@AppManagerActivity).directPackages(
                    installedPackages = packageNames,
                    includeRuBypass = true,
                    includeStealth = false,
                    serverRules = null,
                    installerPackages = installers,
                )
                val bundledSet = RuCompatibilityClassifier(this@AppManagerActivity).directPackages(
                    installedPackages = packageNames,
                    installerPackages = installers,
                )
                val loaded = snapshot.mapNotNull { (packageName, packageInfo) ->
                    coroutineContext.ensureActive()
                    packageInfo.applicationInfo?.let { info ->
                        val bitmap = loadCachedIconBitmap(packageManager, packageName, info)
                        ProxiedApp(
                            pm = packageManager,
                            appInfo = info,
                            packageName = packageName,
                            isRuDirect = packageName in ruDirectSet,
                            iconBitmap = bitmap,
                        )
                    }
                }
                loaded to bundledSet
            }
            cachedBundledPackages = result.second
            apps = sortApps(result.first)
            allAppsEmpty.value = apps.isEmpty()
            loading.value = false
            applyFilter()
        }
    }

    private fun applyFilter() {
        filterJob?.cancel()
        val snapshot = apps
        val query = searchText.value.trim()
        val tab = filterTab.value
        val selected = selectedUids.value
        filterJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                snapshot.asSequence()
                    .filter { app ->
                        when (tab) {
                            AppFilterTab.All -> true
                            AppFilterTab.Selected -> app.uid in selected
                            AppFilterTab.Ru -> app.isRuDirect
                            AppFilterTab.User -> !app.sys
                            AppFilterTab.System -> app.sys
                        }
                    }
                    .filter { app ->
                        query.isBlank() ||
                            app.name.contains(query, ignoreCase = true) ||
                            app.packageName.contains(query, ignoreCase = true) ||
                            app.uid.toString().contains(query)
                    }
                    .map { it.uiModel }
                    .toList()
            }
            filteredUiApps.value = result
        }
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
        if (cachedBundledPackages.isNotEmpty()) return cachedBundledPackages
        val installedPackages = apps
            .map { it.packageName }
            .ifEmpty { cachedApps.keys.toList() }
        val installers = RuCompatibilityClassifier.resolveInstallers(packageManager, installedPackages)
        return RuCompatibilityClassifier(this).directPackages(
            installedPackages = installedPackages,
            installerPackages = installers,
        ).also {
            cachedBundledPackages = it
        }
    }

    private fun applyBundledBypassSelections() {
        val bundledUids = packagesToUids(bundledBypassPackages())
        if (proxyMode.value == AppManagerProxyMode.Proxy || !bypassRuApps.value) {
            selectedUids.value = selectedUids.value - bundledUids
            return
        }
        selectedUids.value = selectedUids.value + bundledUids
    }

    private fun alwaysOnLockdownEnabled(): Boolean {
        return runCatching {
            Settings.Secure.getInt(contentResolver, "always_on_vpn_lockdown", 0) == 1
        }.getOrDefault(false)
    }

    private fun toggleApp(uid: Int) {
        if (proxyMode.value == AppManagerProxyMode.Off) {
            setProxyMode(AppManagerProxyMode.Bypass)
        }
        selectedUids.value = selectedUids.value.toggle(uid)
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
        val bundledPackages = if (proxyMode.value == AppManagerProxyMode.Proxy) {
            emptySet()
        } else {
            bundledBypassPackages()
        }
        val next = apps
            .filter { it.uid in selectedUids.value && it.packageName !in bundledPackages }
            .joinToString("\n") { it.packageName }
        if (DataStore.individual != next) {
            DataStore.individual = next
            pendingReconnect.value = true
        }
    }

    private fun setProxyMode(mode: AppManagerProxyMode) {
        if (proxyMode.value == mode) return
        proxyMode.value = mode
        pendingReconnect.value = true
        when (mode) {
            AppManagerProxyMode.Off -> {
                DataStore.proxyApps = false
            }

            AppManagerProxyMode.Proxy -> {
                DataStore.proxyApps = true
                DataStore.bypass = false
            }

            AppManagerProxyMode.Bypass -> {
                DataStore.proxyApps = true
                DataStore.bypass = true
            }
        }
        applyBundledBypassSelections()
        persistSelections()
        applyFilter()
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
                setProxyMode(if (enabled.toBoolean()) AppManagerProxyMode.Bypass else AppManagerProxyMode.Proxy)
                if (DataStore.individual != importedApps) pendingReconnect.value = true
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
            val packageNames = apps.map { it.packageName }
            val installers = RuCompatibilityClassifier.resolveInstallers(packageManager, packageNames)
            val ruPackages = RuCompatibilityClassifier(this).directPackages(
                installedPackages = packageNames,
                includeRuBypass = true,
                includeStealth = false,
                serverRules = null,
                installerPackages = installers,
            )
            val ruUids = packagesToUids(ruPackages)
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
        filterJob?.cancel()
        super.onDestroy()
    }

    private fun BaseService.State.isSpaceActive(): Boolean {
        return this == BaseService.State.Connecting ||
            this == BaseService.State.Connected ||
            this == BaseService.State.Stopping
    }

    private companion object {
        private val iconCache = ConcurrentHashMap<String, ImageBitmap>()
        private const val ICON_SIZE_PX = 96

        private fun loadCachedIconBitmap(
            pm: PackageManager,
            packageName: String,
            info: ApplicationInfo,
        ): ImageBitmap? {
            iconCache[packageName]?.let { return it }
            val rendered = runCatching {
                info.loadIcon(pm).toBitmap(width = ICON_SIZE_PX, height = ICON_SIZE_PX).asImageBitmap()
            }.getOrNull() ?: return null
            iconCache[packageName] = rendered
            return rendered
        }

        private val cachedApps
            get() = PackageCache.installedPackages.toMutableMap().apply {
                remove(BuildConfig.APPLICATION_ID)
            }
    }
}
