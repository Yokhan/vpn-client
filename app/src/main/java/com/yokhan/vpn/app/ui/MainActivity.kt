package com.yokhan.gates.app.ui

import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.view.KeyEvent
import android.view.View
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentContainerView
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.group.GroupInterfaceAdapter
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import com.yokhan.gates.app.auth.YokhanKeycloakAuth
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.yokhan.gates.app.manifest.YokhanRouteManifestManager
import com.yokhan.gates.app.migration.YokhanPackageMigration
import com.yokhan.gates.app.ui.compose.YokhanServiceStateConsumer
import com.yokhan.gates.app.update.YokhanAppUpdater
import com.yokhan.gates.app.ui.profile.AnyTLSSettingsActivity
import com.yokhan.gates.app.ui.profile.ChainSettingsActivity
import com.yokhan.gates.app.ui.profile.ConfigSettingActivity
import com.yokhan.gates.app.ui.profile.HttpSettingsActivity
import com.yokhan.gates.app.ui.profile.HysteriaSettingsActivity
import com.yokhan.gates.app.ui.profile.MieruSettingsActivity
import com.yokhan.gates.app.ui.profile.NaiveSettingsActivity
import com.yokhan.gates.app.ui.profile.SSHSettingsActivity
import com.yokhan.gates.app.ui.profile.ShadowTLSSettingsActivity
import com.yokhan.gates.app.ui.profile.ShadowsocksSettingsActivity
import com.yokhan.gates.app.ui.profile.SocksSettingsActivity
import com.yokhan.gates.app.ui.profile.TrojanGoSettingsActivity
import com.yokhan.gates.app.ui.profile.TrojanSettingsActivity
import com.yokhan.gates.app.ui.profile.TuicSettingsActivity
import com.yokhan.gates.app.ui.profile.VMessSettingsActivity
import com.yokhan.gates.app.ui.profile.WireGuardSettingsActivity
import com.yokhan.gates.app.ui.shell.YokhanManualProfileProtocol
import com.yokhan.gates.app.ui.shell.YokhanShellActivityRoute
import com.yokhan.gates.app.ui.shell.YokhanMainShell
import com.yokhan.gates.app.ui.shell.YokhanShellDrawerCommand
import com.yokhan.gates.app.ui.shell.YokhanShellRoute
import com.yokhan.gates.app.ui.shell.YokhanShellSubRoute
import moe.matsuri.nb4a.utils.Util
import net.openid.appauth.AuthorizationService

class MainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener,
    DashboardHost {

    private lateinit var fragmentHostView: View
    private val selectedRoute = mutableStateOf(YokhanShellRoute.Home)
    private val shellServiceState = mutableStateOf(BaseService.State.Idle)
    private val drawerCommand = mutableStateOf(YokhanShellDrawerCommand())
    private val shellBanner = mutableStateOf<com.yokhan.gates.app.ui.shell.YokhanShellBanner?>(null)
    private var bannerSequence = 0L
    private var navigationMenuOpen = false
    private var fragmentHostReady = false
    private var pendingInitialFragment = true
    private val dashboardController by lazy { DashboardController(this) }
    private val yokhanAuthService by lazy { YokhanKeycloakAuth.createAuthorizationService(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            YokhanMainShell(
                selectedRoute = selectedRoute.value,
                serviceState = shellServiceState.value,
                drawerCommand = drawerCommand.value,
                onRouteSelected = { route -> displayRoute(route) },
                onActivityRouteSelected = ::openActivityRoute,
                onDrawerOpenChanged = { navigationMenuOpen = it },
                onFragmentHostReady = { host ->
                    fragmentHostView = host
                    onFragmentHostReady(host, savedInstanceState)
                },
                banner = shellBanner.value,
                onDismissBanner = { shellBanner.value = null },
                content = { dashboardController.Content() },
            )
        }
        onBackPressedDispatcher.addCallback {
            if (navigationMenuOpen) {
                closeNavigationMenu()
                return@addCallback
            }
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
                return@addCallback
            }
            if (selectedRoute.value == YokhanShellRoute.Home) {
                moveTaskToBack(true)
            } else {
                displayRoute(YokhanShellRoute.Home)
            }
        }

        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0 &&
                supportFragmentManager.findFragmentById(R.id.fragment_holder) == null &&
                selectedRoute.value != YokhanShellRoute.Home
            ) {
                selectedRoute.value = YokhanShellRoute.Home
            }
        }

        changeState(BaseService.State.Idle)
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)
        GroupManager.userInterface = GroupInterfaceAdapter(this)

        handleIncomingIntent(intent)
        lifecycleScope.launch { YokhanKeycloakAuth.restoreSession(this@MainActivity) }

        // sdk 33 notification
        if (Build.VERSION.SDK_INT >= 33) {
            val checkPermission =
                ContextCompat.checkSelfPermission(this@MainActivity, POST_NOTIFICATIONS)
            if (checkPermission != PackageManager.PERMISSION_GRANTED) {
                //еЉЁжЂЃз”іиЇ·
                ActivityCompat.requestPermissions(
                    this@MainActivity, arrayOf(POST_NOTIFICATIONS), 0
                )
            }
        }

        if (isPreview) {
            MaterialAlertDialogBuilder(this)
                .setTitle(BuildConfig.PRE_VERSION_NAME)
                .setMessage(R.string.preview_version_hint)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        YokhanAppUpdater.checkOnLaunch(this)
        YokhanRouteManifestManager.refreshOnLaunch(this)
        YokhanPackageMigration.showIntroIfNeeded(this)
    }

    private fun onFragmentHostReady(host: FragmentContainerView, savedInstanceState: Bundle?) {
        if (fragmentHostReady) return
        fragmentHostReady = true
        if (!pendingInitialFragment) return
        pendingInitialFragment = false
        host.post {
            if (
                savedInstanceState == null ||
                supportFragmentManager.findFragmentById(R.id.fragment_holder) == null
            ) {
                displayRoute(YokhanShellRoute.Home)
            }
        }
    }

    fun refreshNavMenu(_clashApi: Boolean) {
        // The Compose shell owns navigation visibility. Keep this compatibility hook
        // until the legacy settings routes are migrated away from direct activity calls.
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (YokhanKeycloakAuth.handleAuthorizationIntent(this, yokhanAuthService, intent)) {
            setIntent(Intent(this, MainActivity::class.java))
            return
        }
        if (intent.action != Intent.ACTION_VIEW) return

        val uri = intent.data ?: return

        runOnDefaultDispatcher {
            if (uri.scheme == "sn" && uri.host == "subscription" || uri.scheme == "clash") {
                importSubscription(uri)
            } else {
                importProfile(uri)
            }
        }
    }

    fun urlTest(): Int {
        if (!DataStore.serviceState.connected || connection.service == null) {
            error("not started")
        }
        return connection.service!!.urlTest()
    }

    suspend fun importSubscription(uri: Uri) {
        val group: ProxyGroup

        val url = uri.getQueryParameter("url")
        if (!url.isNullOrBlank()) {
            group = ProxyGroup(type = GroupType.SUBSCRIPTION)
            val subscription = SubscriptionBean()
            group.subscription = subscription

            // cleartext format
            subscription.link = url
            group.name = uri.getQueryParameter("name")
        } else {
            val data = uri.encodedQuery.takeIf { !it.isNullOrBlank() } ?: return
            try {
                group = KryoConverters.deserialize(
                    ProxyGroup().apply { export = true }, Util.zlibDecompress(Util.b64Decode(data))
                ).apply {
                    export = false
                }
            } catch (e: Exception) {
                onMainDispatcher {
                    alert(e.readableMessage).show()
                }
                return
            }
        }

        val name = group.name.takeIf { !it.isNullOrBlank() } ?: group.subscription?.link
        ?: group.subscription?.token
        if (name.isNullOrBlank()) return

        group.name = group.name.takeIf { !it.isNullOrBlank() }
            ?: ("Subscription #" + System.currentTimeMillis())

        onMainDispatcher {

            displayRoute(YokhanShellRoute.Group)

            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.subscription_import)
                .setMessage(getString(R.string.subscription_import_message, name))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportSubscription(group)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

        }

    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        GroupManager.createGroup(subscription)
        GroupUpdater.startUpdate(subscription, true)
    }

    suspend fun importProfile(uri: Uri) {
        val profile = try {
            parseProxies(uri.toString()).getOrNull(0) ?: error(getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            onMainDispatcher {
                alert(e.readableMessage).show()
            }
            return
        }

        onMainDispatcher {
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportProfile(profile)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

    }

    private suspend fun finishImportProfile(profile: AbstractBean) {
        val targetId = DataStore.ensureSelectedGroupForImport()

        ProfileManager.createProfile(targetId, profile)

        onMainDispatcher {
            displayRoute(YokhanShellRoute.Configuration)

            snackbar(resources.getQuantityString(R.plurals.added, 1, 1)).show()
        }
    }

    override fun missingPlugin(profileName: String, pluginName: String) {
        val pluginEntity = PluginEntry.find(pluginName)

        // unknown exe or neko plugin
        if (pluginEntity == null) {
            snackbar(getString(R.string.plugin_unknown, pluginName)).show()
            return
        }

        // official exe

        MaterialAlertDialogBuilder(this).setTitle(R.string.missing_plugin)
            .setMessage(
                getString(
                    R.string.profile_requiring_plugin, profileName, pluginEntity.displayName
                )
            )
            .setPositiveButton(R.string.action_download) { _, _ ->
                showDownloadDialog(pluginEntity)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.action_learn_more) { _, _ ->
                launchCustomTab(BuildConfig.YOKHAN_APP_DOWNLOAD_URL)
            }
            .show()
    }

    private fun showDownloadDialog(pluginEntry: PluginEntry) {
        var index = 0
        var playIndex = -1
        var fdroidIndex = -1

        val items = mutableListOf<String>()
        if (pluginEntry.downloadSource.playStore) {
            items.add(getString(R.string.install_from_play_store))
            playIndex = index++
        }
        if (pluginEntry.downloadSource.fdroid) {
            items.add(getString(R.string.install_from_fdroid))
            fdroidIndex = index++
        }

        items.add(getString(R.string.download))
        val downloadIndex = index

        MaterialAlertDialogBuilder(this).setTitle(pluginEntry.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    playIndex -> launchCustomTab("https://play.google.com/store/apps/details?id=${pluginEntry.packageName}")
                    fdroidIndex -> launchCustomTab("https://f-droid.org/packages/${pluginEntry.packageName}/")
                    downloadIndex -> launchCustomTab(pluginEntry.downloadSource.downloadLink)
                }
            }
            .show()
    }

    @SuppressLint("CommitTransaction")
    fun displayFragment(fragment: ToolbarFragment, addToBackStack: Boolean = false) {
        if (!addToBackStack && supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate(
                null,
                FragmentManager.POP_BACK_STACK_INCLUSIVE
            )
        }
        supportFragmentManager.beginTransaction().apply {
            replace(R.id.fragment_holder, fragment)
            if (addToBackStack) addToBackStack(fragment::class.java.name)
        }.commitAllowingStateLoss()
        (fragment as? YokhanServiceStateConsumer)?.onShellServiceStateChanged(shellServiceState.value)
        closeNavigationMenu()
    }

    fun openSubRoute(route: YokhanShellSubRoute): Boolean {
        val fragment = when (route) {
            YokhanShellSubRoute.Profiles -> ConfigurationFragment()
            YokhanShellSubRoute.Groups -> GroupFragment()
            YokhanShellSubRoute.Routes -> RouteFragment()
            YokhanShellSubRoute.UsbBridge -> UsbBridgeSettingsFragment()
            YokhanShellSubRoute.AdvancedSettings -> SettingsAdvancedFragment()
        }
        displayFragment(fragment, addToBackStack = true)
        return true
    }

    fun openActivityRoute(route: YokhanShellActivityRoute): Boolean {
        val intent = when (route) {
            YokhanShellActivityRoute.Scanner -> Intent(this, ScannerActivity::class.java)
            YokhanShellActivityRoute.GroupCreate -> Intent(this, GroupSettingsActivity::class.java)
            is YokhanShellActivityRoute.GroupEdit -> {
                Intent(this, GroupSettingsActivity::class.java).apply {
                    putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, route.groupId)
                }
            }
            YokhanShellActivityRoute.RouteCreate -> Intent(this, RouteSettingsActivity::class.java)
            is YokhanShellActivityRoute.RouteEdit -> {
                Intent(this, RouteSettingsActivity::class.java).apply {
                    putExtra(RouteSettingsActivity.EXTRA_ROUTE_ID, route.routeId)
                }
            }
            YokhanShellActivityRoute.RouteAssets -> Intent(this, AssetsActivity::class.java)
            YokhanShellActivityRoute.AppManager -> Intent(this, AppManagerActivity::class.java)
            YokhanShellActivityRoute.RuCompatibility -> Intent(this, RuCompatibilityActivity::class.java)
            is YokhanShellActivityRoute.AddManualProfile -> {
                when (route.protocol) {
                    YokhanManualProfileProtocol.Socks -> Intent(this, SocksSettingsActivity::class.java)
                    YokhanManualProfileProtocol.Http -> Intent(this, HttpSettingsActivity::class.java)
                    YokhanManualProfileProtocol.Shadowsocks -> Intent(this, ShadowsocksSettingsActivity::class.java)
                    YokhanManualProfileProtocol.VMess -> Intent(this, VMessSettingsActivity::class.java)
                    YokhanManualProfileProtocol.VLESS -> {
                        Intent(this, VMessSettingsActivity::class.java).apply {
                            putExtra("vless", true)
                        }
                    }
                    YokhanManualProfileProtocol.Trojan -> Intent(this, TrojanSettingsActivity::class.java)
                    YokhanManualProfileProtocol.TrojanGo -> Intent(this, TrojanGoSettingsActivity::class.java)
                    YokhanManualProfileProtocol.Mieru -> Intent(this, MieruSettingsActivity::class.java)
                    YokhanManualProfileProtocol.Naive -> Intent(this, NaiveSettingsActivity::class.java)
                    YokhanManualProfileProtocol.Hysteria -> Intent(this, HysteriaSettingsActivity::class.java)
                    YokhanManualProfileProtocol.Tuic -> Intent(this, TuicSettingsActivity::class.java)
                    YokhanManualProfileProtocol.ShadowTLS -> Intent(this, ShadowTLSSettingsActivity::class.java)
                    YokhanManualProfileProtocol.AnyTLS -> Intent(this, AnyTLSSettingsActivity::class.java)
                    YokhanManualProfileProtocol.SSH -> Intent(this, SSHSettingsActivity::class.java)
                    YokhanManualProfileProtocol.WireGuard -> Intent(this, WireGuardSettingsActivity::class.java)
                    YokhanManualProfileProtocol.Config -> Intent(this, ConfigSettingActivity::class.java)
                    YokhanManualProfileProtocol.Chain -> Intent(this, ChainSettingsActivity::class.java)
                }
            }
            is YokhanShellActivityRoute.EditProfile -> {
                route.profile.settingIntent(this, route.isSubscription)
            }
        }
        startActivity(intent)
        return true
    }

    fun displayRoute(route: YokhanShellRoute): Boolean {
        if (selectedRoute.value == route && route != YokhanShellRoute.Home) {
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStackImmediate(
                    null,
                    FragmentManager.POP_BACK_STACK_INCLUSIVE,
                )
            }
            closeNavigationMenu()
            return true
        }
        when (route) {
            YokhanShellRoute.Home -> {
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStackImmediate(
                        null,
                        FragmentManager.POP_BACK_STACK_INCLUSIVE,
                    )
                }
                dashboardController.onShown()
                closeNavigationMenu()
            }

            YokhanShellRoute.Configuration -> {
                displayFragment(ConfigurationFragment())
            }

            YokhanShellRoute.Group -> displayFragment(GroupFragment())
            YokhanShellRoute.Route -> displayFragment(RouteFragment())
            YokhanShellRoute.Settings -> displayFragment(SettingsFragment())
            YokhanShellRoute.Traffic -> displayFragment(WebviewFragment())
            YokhanShellRoute.Tools -> displayFragment(ToolsFragment())
            YokhanShellRoute.Logcat -> {
                if (!BuildConfig.DEBUG) return false
                displayFragment(LogcatFragment())
            }
            YokhanShellRoute.Faq -> {
                launchCustomTab(BuildConfig.YOKHAN_APP_DOWNLOAD_URL)
                return false
            }

            YokhanShellRoute.About -> displayFragment(AboutFragment())
            YokhanShellRoute.Promotion -> {
                launchCustomTab(BuildConfig.YOKHAN_APP_DOWNLOAD_URL)
                return false
            }

        }
        selectedRoute.value = route
        return true
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
    ) {
        DataStore.serviceState = state
        shellServiceState.value = state

        dashboardController.updateConnectionState(state)
        if (state == BaseService.State.Connected || msg != null) {
            val appContext = applicationContext
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                if (state == BaseService.State.Connected) {
                    YokhanRouteManifestManager.recordTunnelConnected(appContext)
                }
                if (msg != null) {
                    YokhanRouteManifestManager.recordConnectionFailure(appContext)
                }
            }
        }
        (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? YokhanServiceStateConsumer)
            ?.onShellServiceStateChanged(state)
        if (msg != null) {
            val formatted = getString(R.string.vpn_error, msg)
            if (selectedRoute.value == YokhanShellRoute.Home) {
                showDashboardMessage(formatted)
            } else {
                snackbar(formatted).show()
            }
        }
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        val root = if (
            selectedRoute.value != YokhanShellRoute.Home &&
            ::fragmentHostView.isInitialized &&
            fragmentHostView.isShown
        ) {
            fragmentHostView
        } else {
            findViewById(android.R.id.content) ?: window.decorView
        }
        return Snackbar.make(root, text, Snackbar.LENGTH_LONG)
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg)
    }

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    override fun onServiceConnected(service: ISagerNetService) = changeState(
        try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
    )

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) {
            if (selectedRoute.value == YokhanShellRoute.Home) {
                showDashboardMessage(R.string.vpn_permission_denied)
            } else {
                snackbar(R.string.vpn_permission_denied).show()
            }
        }
    }

    // may NOT called when app is in background
    // ONLY do UI update here, write DB in bg process
    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        dashboardController.updateSpeeds(stats.txRateProxy, stats.rxRateProxy)
    }

    override fun cbTrafficUpdate(data: TrafficData) {
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(data)
        }
    }

    override fun cbSelectorUpdate(id: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
        }
        dashboardController.refreshDashboard()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.SERVICE_MODE -> onBinderDied()
            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
                if (DataStore.serviceState.canStop) {
                    snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                        SagerNet.reloadServiceAsync()
                    }.show()
                }
            }
        }
        when (key) {
            Key.SERVICE_MODE, Key.PROFILE_ID, Key.PROFILE_GROUP -> {
                dashboardController.refreshDashboard()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        YokhanAppUpdater.resumePendingInstall(this)
        if (selectedRoute.value == YokhanShellRoute.Home) {
            dashboardController.onShown()
        }
    }

    override fun onStart() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
    }

    override fun onStop() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        dashboardController.dispose()
        yokhanAuthService.dispose()
        GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (super.onKeyDown(keyCode, event)) return true

        val fragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }

    fun toggleConnection() {
        if (DataStore.serviceState.canStop) {
            SagerNet.stopServiceAsync()
        } else {
            connect.launch(null)
        }
    }

    fun openNavigationMenu() {
        closeNavigationMenu()
    }

    private fun closeNavigationMenu() {
        val current = drawerCommand.value
        if (current.open) {
            drawerCommand.value = YokhanShellDrawerCommand(current.sequence + 1, open = false)
        }
    }

    override fun openDashboardMenu() {
        openNavigationMenu()
    }

    override fun toggleDashboardConnection() {
        toggleConnection()
    }

    fun runConnectionTest() {
        dashboardController.runConnectionTest()
    }

    fun startYokhanLogin() {
        YokhanKeycloakAuth.syncOrLogin(this, yokhanAuthService)
    }

    fun startYokhanForceLogin() {
        YokhanKeycloakAuth.startLogin(this, yokhanAuthService)
    }

    fun signOutYokhan() {
        YokhanKeycloakAuth.signOut(this)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val incomingIntent = intent ?: return
        if (YokhanKeycloakAuth.handleAuthorizationIntent(this, yokhanAuthService, incomingIntent)) {
            setIntent(Intent(this, MainActivity::class.java))
            return
        }
        if (incomingIntent.action == Intent.ACTION_VIEW) {
            onNewIntent(incomingIntent)
        }
    }

    fun onYokhanAuthImportComplete(profileCount: Int) {
        dashboardController.refreshDashboard()
        displayRoute(YokhanShellRoute.Home)
        showDashboardMessage(getString(R.string.yokhan_auth_import_success, profileCount))
    }

    fun onYokhanBootstrapComplete() {
        dashboardController.refreshDashboard()
        displayRoute(YokhanShellRoute.Home)
        if (!DataStore.serviceState.canStop) {
            connect.launch(null)
        }
        showDashboardMessage(getString(R.string.wise_tree_success))
    }

    override fun runDashboardConnectionTest() {
        dashboardController.runConnectionTest()
    }

    override fun openDashboardConfiguration() {
        displayRoute(YokhanShellRoute.Configuration)
    }

    override fun updateDashboardProtocol(protocol: String) {
    }

    override fun showDashboardMessage(message: CharSequence) {
        val text = message.toString()
        val isError = text.contains("не удалось", ignoreCase = true) ||
            text.contains("нет ответа", ignoreCase = true) ||
            text.contains("не прошёл", ignoreCase = true) ||
            text.contains("ошибка", ignoreCase = true) ||
            text.contains("запрещ", ignoreCase = true) ||
            text.contains("откл", ignoreCase = true) && text.contains("разреш", ignoreCase = true)
        val publish = {
            bannerSequence += 1L
            shellBanner.value = com.yokhan.gates.app.ui.shell.YokhanShellBanner(
                id = bannerSequence,
                text = text,
                isError = isError,
            )
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            publish()
        } else {
            runOnUiThread { publish() }
        }
    }

    override fun showDashboardMessage(messageRes: Int) {
        showDashboardMessage(getString(messageRes))
    }

}
