package io.nekohasekai.sagernet.bg

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.IpPrefix
import android.net.NetworkCapabilities
import android.net.ProxyInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.Settings
import com.yokhan.gates.app.routing.RuCompatibilityClassifier
import com.yokhan.gates.app.routing.VpnAppRoutePlanner
import com.yokhan.gates.app.routing.WholeTrafficPolicy
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.ktx.*
import com.yokhan.gates.app.ui.VpnRequestActivity
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.utils.Subnet
import io.nekohasekai.sagernet.bg.proto.XhttpSidecarBridge
import moe.matsuri.nb4a.proxy.config.ConfigBean
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import android.net.VpnService as BaseVpnService

class VpnService : BaseVpnService(),
    BaseService.Interface {

    companion object {

        const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        const val PRIVATE_VLAN4_ROUTER = "172.19.0.2"
        const val FAKEDNS_VLAN4_CLIENT = "198.18.0.0"
        const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
        const val PRIVATE_VLAN6_ROUTER = "fdfe:dcba:9876::2"

        private val cachedEntryHosts = ConcurrentHashMap<String, Pair<Long, List<Inet4Address>>>()
        private const val ENTRY_HOST_TTL_MS = 5 * 60 * 1000L

        internal fun resolveEntryAddressesCached(host: String): List<Inet4Address> {
            val now = android.os.SystemClock.elapsedRealtime()
            val cached = cachedEntryHosts[host]
            if (cached != null && now - cached.first < ENTRY_HOST_TTL_MS && cached.second.isNotEmpty()) {
                return cached.second
            }
            val resolved = runCatching {
                InetAddress.getAllByName(host).filterIsInstance<Inet4Address>()
            }.getOrDefault(emptyList())
            if (resolved.isNotEmpty()) {
                cachedEntryHosts[host] = now to resolved
                return resolved
            }
            return cached?.second.orEmpty()
        }

    }

    var conn: ParcelFileDescriptor? = null

    private var metered = false

    override var upstreamInterfaceName: String? = null

    override suspend fun startProcesses() {
        DataStore.vpnService = this
        super.startProcesses() // launch proxy instance
    }

    override var wakeLock: PowerManager.WakeLock? = null

    @SuppressLint("WakelockTimeout")
    override fun acquireWakeLock() {
        wakeLock = SagerNet.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sagernet:vpn")
            .apply { acquire() }
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override fun killProcesses() {
        conn?.close()
        conn = null
        super.killProcesses()
    }

    override fun onBind(intent: Intent) = when (intent.action) {
        SERVICE_INTERFACE -> super<BaseVpnService>.onBind(intent)
        else -> super<BaseService.Interface>.onBind(intent)
    }

    override val data = BaseService.Data(this)
    override val tag = "SagerNetVpnService"
    override fun createNotification(profileName: String) =
        ServiceNotification(this, profileName, "service-vpn")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DataStore.serviceMode == Key.MODE_VPN) {
            if (prepare(this) != null) {
                startActivity(
                    Intent(
                        this, VpnRequestActivity::class.java
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else return super<BaseService.Interface>.onStartCommand(intent, flags, startId)
        }
        stopRunner()
        return Service.START_NOT_STICKY
    }

    inner class NullConnectionException : NullPointerException(),
        BaseService.ExpectedException {
        override fun getLocalizedMessage() = getString(R.string.reboot_required)
    }

    fun startVpn(tunOptionsJson: String, tunPlatformOptionsJson: String): Int {
//        Logs.d(tunOptionsJson)
//        Logs.d(tunPlatformOptionsJson)
//        val tunOptions = JSONObject(tunOptionsJson)

        // address & route & MTU ...... use NB4A GUI config
        val builder = Builder().setConfigureIntent(SagerNet.configureIntent(this))
            .setSession(getString(R.string.app_name))
            .setMtu(DataStore.mtu)
        val policy = WholeTrafficPolicy(DataStore.wholeTrafficMode)

        // address
        builder.addAddress(PRIVATE_VLAN4_CLIENT, 30)
        // Always own an IPv6 address. Without it Android keeps using the physical
        // network for IPv6 and user traffic leaks around the tunnel.
        builder.addAddress(PRIVATE_VLAN6_CLIENT, 126)
        builder.addDnsServer(PRIVATE_VLAN4_ROUTER)

        // route
        if (policy.applyLanBypass && DataStore.bypassLan) {
            resources.getStringArray(R.array.bypass_private_route).forEach {
                val subnet = Subnet.fromString(it)!!
                builder.addRoute(subnet.address.hostAddress!!, subnet.prefixSize)
            }
            builder.addRoute(PRIVATE_VLAN4_ROUTER, 32)
            builder.addRoute(FAKEDNS_VLAN4_CLIENT, 15)
            // https://issuetracker.google.com/issues/149636790
            builder.addRoute("2000::", 3)
        } else {
            builder.addRoute("0.0.0.0", 0)
        }
        // Capture every IPv6 destination even when IPv6 is disabled. The core
        // rejects it in that mode, so it can never bypass the tunnel.
        builder.addRoute("::", 0)

        // The Xray sidecar dials the real entry from its own process. Exclude that
        // address from the tunnel, otherwise its socket is captured by the TUN and
        // the XHTTP flow loops before reaching the server.
        if (Build.VERSION.SDK_INT >= 33) {
            val entity = runCatching { SagerDatabase.proxyDao.getById(DataStore.currentProfile) }.getOrNull()
            val bean = runCatching { entity?.requireBean() }.getOrNull()
            if (bean is ConfigBean && bean.type == 0) {
                XhttpSidecarBridge.entryHost(bean.config)?.let { host ->
                    val addresses = resolveEntryAddressesCached(host)
                    addresses.forEach { address ->
                        runCatching { builder.excludeRoute(IpPrefix(address, 32)) }
                    }
                }
            }
        }

        updateUnderlyingNetwork(builder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(metered)

        // app route
        if (!policy.applyPerAppSplit) {
            // Compliant default: no per-app allow/disallow split, no RU/stealth
            // auto-bypass and no server-provided exclude list. The core protects
            // its own transport sockets, so no app exception is needed here.
            Logs.d("Whole-traffic mode: all apps routed through the tunnel")
        } else {
            val packageName = packageName
            val proxyApps = DataStore.proxyApps
            val configuredBypass = DataStore.bypass
            val hasRuBypass = DataStore.enableRuBypass
            val stealthMode = DataStore.stealthMode
            val serverRules = DataStore.serverExcludePackages
            val hasServerRules = !serverRules.isNullOrBlank()
            val workaroundSYSTEM = false /* DataStore.tunImplementation == TunImplementation.SYSTEM */
            val needBypassRootUid = workaroundSYSTEM || data.proxy!!.config.trafficMap.values.any {
                it[0].hysteriaBean?.protocol == HysteriaBean.PROTOCOL_FAKETCP
            }

            if (proxyApps || hasRuBypass || hasServerRules || stealthMode || needBypassRootUid) {
                val allApps by lazy {
                    val cached = PackageCache.installedPackages
                    if (cached.isNotEmpty()) {
                        cached.keys.filter { it != packageName }
                    } else {
                        packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS).filter {
                            when (it.packageName) {
                                packageName -> false
                                "android" -> true
                                else -> it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
                            }
                        }.map {
                            it.packageName
                        }
                    }
                }
                val installerPackages = if (hasRuBypass) {
                    RuCompatibilityClassifier.resolveInstallers(packageManager, allApps)
                } else {
                    emptyMap()
                }
                val autoBypassPackages = RuCompatibilityClassifier(this).directPackages(
                    installedPackages = allApps,
                    includeRuBypass = hasRuBypass,
                    includeStealth = stealthMode,
                    serverRules = serverRules,
                    installerPackages = installerPackages,
                )
                if (hasRuBypass && autoBypassPackages.isNotEmpty()) {
                    Logs.d("RU auto-bypass: ${autoBypassPackages.size} apps classified direct")
                }

                val manualPackages = if (proxyApps) {
                    DataStore.individual.split('\n').filter { it.isNotBlank() }.toSet()
                } else {
                    emptySet()
                }
                val alwaysOnLockdown = runCatching {
                    Settings.Secure.getInt(contentResolver, "always_on_vpn_lockdown", 0) == 1
                }.getOrDefault(false)

                val plan = VpnAppRoutePlanner.build(
                    ownPackageName = packageName,
                    wholeTrafficMode = DataStore.wholeTrafficMode,
                    proxyApps = proxyApps,
                    configuredBypass = configuredBypass,
                    needBypassRootUid = needBypassRootUid,
                    manualPackages = manualPackages,
                    allApps = allApps,
                    autoBypassPackages = autoBypassPackages,
                    alwaysOnLockdown = alwaysOnLockdown,
                )

                if (plan.blockedBySystemLockdown) {
                    Logs.w("OS Always-on VPN Lockdown active: moved ${plan.inTunnelDirectPackages.size} direct apps to in-tunnel UID bypass")
                }

                val added = mutableListOf<String>()
                val targetPackages = if (plan.effectiveBypass) plan.disallowedPackages else plan.allowedPackages
                targetPackages.forEach { pkg ->
                    try {
                        if (plan.effectiveBypass) {
                            builder.addDisallowedApplication(pkg)
                        } else {
                            builder.addAllowedApplication(pkg)
                        }
                        added.add(pkg)
                    } catch (ex: PackageManager.NameNotFoundException) {
                        Logs.w(ex)
                    }
                }

                if (plan.effectiveBypass) {
                    Logs.d("Add bypass (${added.size}): ${added.joinToString(", ")}")
                } else {
                    Logs.d("Add allow (${added.size}): ${added.joinToString(", ")}")
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && policy.allowInboundListeners && DataStore.appendHttpProxy && DataStore.enableLocalInbound && !DataStore.stealthMode) {
            builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOCALHOST, DataStore.mixedPort))
        }

        metered = DataStore.meteredNetwork
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(metered)
        conn = builder.establish() ?: throw NullConnectionException()

        return conn!!.fd
    }

    fun updateUnderlyingNetwork(builder: Builder? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            var underlying = SagerNet.underlyingNetwork
            if (underlying == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val active = SagerNet.connectivity.activeNetwork
                val caps = active?.let { SagerNet.connectivity.getNetworkCapabilities(it) }
                if (active != null && caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    underlying = active
                    SagerNet.underlyingNetwork = active
                }
            }
            val targetNetworks = underlying?.let { arrayOf(it) }
            if (builder != null) {
                if (targetNetworks != null) builder.setUnderlyingNetworks(targetNetworks)
            } else {
                setUnderlyingNetworks(targetNetworks)
            }
        }
    }

    override fun onRevoke() = stopRunner()

    override fun onDestroy() {
        DataStore.vpnService = null
        super.onDestroy()
        data.binder.close()
    }
}
