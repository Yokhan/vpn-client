package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.compose.YokhanRouteAction
import io.nekohasekai.sagernet.ui.compose.YokhanSelectableApp
import io.nekohasekai.sagernet.ui.compose.YokhanSelectableAppsScreen
import io.nekohasekai.sagernet.utils.PackageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class AppListActivity : ThemedActivity() {

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
    private val showSystemApps = mutableStateOf(false)
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
        YokhanSelectableAppsScreen(
            title = getString(R.string.select_apps),
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

    private fun initProxiedUids(str: String = DataStore.routePackages) {
        selectedUids.value = packagesToUids(str)
    }

    private fun packagesToUids(str: String): Set<Int> {
        val installedApps = cachedApps
        return str.lineSequence()
            .mapNotNull { line -> installedApps[line]?.applicationInfo?.uid }
            .toSet()
    }

    private fun toggleApp(uid: Int) {
        selectedUids.value = selectedUids.value.toggle(uid)
        persistSelections()
        applyFilter()
    }

    private fun invertSelections() {
        selectedUids.value = apps
            .map { it.uid }
            .toSet()
            .let { allUids -> allUids - selectedUids.value + (selectedUids.value - allUids) }
        apps = sortApps(apps)
        persistSelections()
        applyFilter()
    }

    private fun clearSelections() {
        selectedUids.value = emptySet()
        apps = sortApps(apps)
        persistSelections()
        applyFilter()
    }

    private fun persistSelections() {
        DataStore.routePackages = apps
            .filter { it.uid in selectedUids.value }
            .joinToString("\n") { it.packageName }
    }

    private fun exportClipboard() {
        val success = SagerNet.trySetPrimaryClip("false\n${DataStore.routePackages}")
        showSnackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
    }

    private fun importClipboard() {
        val proxiedAppString = SagerNet.clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        if (!proxiedAppString.isNullOrEmpty()) {
            val i = proxiedAppString.indexOf('\n')
            try {
                val imported = if (i < 0) "" else proxiedAppString.substring(i + 1)
                DataStore.routePackages = imported
                initProxiedUids(imported)
                showSnackbar(R.string.action_import_msg)
                applyFilter()
                return
            } catch (_: IllegalArgumentException) {
            }
        }
        showSnackbar(R.string.action_import_err)
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
