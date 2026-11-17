package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.AbstractInstance
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildHysteria1Config
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.mieru.buildMieruConfig
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.buildNaiveConfig
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.trojan_go.buildTrojanGoConfig
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.*
import libcore.BoxInstance
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import java.io.File

abstract class BoxInstance(
    val profile: ProxyEntity
) : AbstractInstance {

    lateinit var config: ConfigBuildResult
    lateinit var box: BoxInstance

    val pluginPath = hashMapOf<String, PluginManager.InitResult>()
    val pluginConfigs = hashMapOf<Int, Pair<Int, String>>()
    val externalInstances = hashMapOf<Int, AbstractInstance>()
    open lateinit var processes: GuardedProcessPool
    private var cacheFiles = ArrayList<File>()
    private var xraySidecar: XraySidecarSupervisor? = null
    private var xhttpEntryRelay: XhttpEntryRelay? = null
    private var activeXraySpec: io.nekohasekai.sagernet.fmt.xray.XrayRouteSpec? = null
    private var activeXrayConfigDir: File? = null
    private val xrayLifecycleLock = Any()
    private val xrayClosed = java.util.concurrent.atomic.AtomicBoolean(false)

    companion object {
        private const val XHTTP_SIDECAR_PORT = 28080
        private const val XHTTP_MAPPING_PORT = 28081
    }

    fun isInitialized(): Boolean {
        return ::config.isInitialized && ::box.isInitialized
    }

    protected fun initPlugin(name: String): PluginManager.InitResult {
        return pluginPath.getOrPut(name) { PluginManager.init(name)!! }
    }

    protected open fun buildConfig() {
        config = buildConfig(profile)
        if (profile.type == ProxyEntity.TYPE_CONFIG) {
            startXhttpSidecarIfNeeded()
        }
    }

    /**
     * XHTTP cannot run inside sing-box. When the imported raw profile selects the `xhttp` route,
     * the route is rewritten to a loopback SOCKS egress and an Xray child owns the transport.
     * Failing to start the child fails the connection instead of silently falling back.
     */
    private fun startXhttpSidecarIfNeeded() {
        val plan = XhttpSidecarBridge.build(
            config.config,
            XHTTP_SIDECAR_PORT,
            XHTTP_MAPPING_PORT,
        ) ?: return
        val supervisor = XraySidecarPlatform.createSupervisor(
            logger = { line ->
                Logs.d("[xhttp] $line")
                if (BuildConfig.DEBUG) android.util.Log.w("GatesXray", line)
            },
        ) ?: throw IllegalStateException(
            "XHTTP route requires the Xray sidecar, which is unavailable in this build",
        )
        val relay = if (plan.entryHost.isNotBlank() && plan.entryPort > 0) {
            XhttpEntryRelay(
                targetHost = plan.entryHost,
                targetPort = plan.entryPort,
                addressResolver = { host ->
                    SagerNet.underlyingNetwork?.getByName(host)
                        ?: java.net.InetAddress.getByName(host)
                },
                socketBinder = { socket ->
                    SagerNet.underlyingNetwork?.bindSocket(socket)
                },
                socketProtector = { socket ->
                    io.nekohasekai.sagernet.database.DataStore.vpnService?.protect(socket)
                },
                logger = { line ->
                    Logs.d("[xhttp-relay] $line")
                    if (BuildConfig.DEBUG) android.util.Log.w("GatesXrayRelay", line)
                },
            )
        } else {
            null
        }
        val effectiveSpec = if (relay != null) {
            plan.spec.copy(
                entryHost = "127.0.0.1",
                entryPort = relay.localPort,
            )
        } else {
            plan.spec
        }
        val configDir = File(SagerNet.application.cacheDir, "xray-sidecar")
        val result = try {
            synchronized(xrayLifecycleLock) {
                if (xrayClosed.get()) {
                    relay?.close()
                    return
                }
                supervisor.start(effectiveSpec, configDir).also { res ->
                    if (res is XrayStartResult.Ready) {
                        xhttpEntryRelay = relay
                        xraySidecar = supervisor
                        activeXraySpec = effectiveSpec
                        activeXrayConfigDir = configDir
                        config.config = plan.configJson
                    }
                }
            }
        } catch (t: Throwable) {
            relay?.close()
            throw t
        }
        if (result is XrayStartResult.Rejected) {
            relay?.close()
            throw IllegalStateException(
                "XHTTP sidecar failed to start: ${result.failure.detail}",
            )
        }
    }

    fun restartXhttpSidecarOnNetworkChange() {
        synchronized(xrayLifecycleLock) {
            if (xrayClosed.get()) return
            val supervisor = xraySidecar ?: return
            val spec = activeXraySpec ?: return
            val configDir = activeXrayConfigDir ?: return
            Logs.d("[xhttp] resetting sidecar and entry relay after network change")
            xhttpEntryRelay?.resetConnections()
            supervisor.stop(XrayStopReason.Failover)
            if (xrayClosed.get()) return
            when (val result = supervisor.start(spec, configDir)) {
                is XrayStartResult.Ready -> {
                    Logs.d("[xhttp] sidecar restarted cleanly after network change")
                }

                is XrayStartResult.Rejected -> {
                    Logs.d("[xhttp] sidecar restart after network change rejected: ${result.failure.detail}")
                }
            }
        }
    }

    protected open suspend fun loadConfig() {
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

    open suspend fun init() {
        buildConfig()
        for ((chain) in config.externalIndex) {
            chain.entries.forEachIndexed { index, (port, profile) ->
                when (val bean = profile.requireBean()) {
                    is TrojanGoBean -> {
                        initPlugin("trojan-go-plugin")
                        pluginConfigs[port] = profile.type to bean.buildTrojanGoConfig(port)
                    }

                    is MieruBean -> {
                        initPlugin("mieru-plugin")
                        pluginConfigs[port] = profile.type to bean.buildMieruConfig(port)
                    }

                    is NaiveBean -> {
                        initPlugin("naive-plugin")
                        pluginConfigs[port] = profile.type to bean.buildNaiveConfig(port)
                    }

                    is HysteriaBean -> {
                        initPlugin("hysteria-plugin")
                        pluginConfigs[port] = profile.type to bean.buildHysteria1Config(port) {
                            File(
                                app.cacheDir, "hysteria_" + SystemClock.elapsedRealtime() + ".ca"
                            ).apply {
                                parentFile?.mkdirs()
                                cacheFiles.add(this)
                            }
                        }
                    }
                }
            }
        }
        loadConfig()
    }

    override fun launch() {
        // TODO move, this is not box
        val cacheDir = File(SagerNet.application.cacheDir, "tmpcfg")
        cacheDir.mkdirs()

        for ((chain) in config.externalIndex) {
            chain.entries.forEachIndexed { index, (port, profile) ->
                val bean = profile.requireBean()
                val needChain = index != chain.size - 1
                val (profileType, config) = pluginConfigs[port] ?: (0 to "")

                when {
                    externalInstances.containsKey(port) -> {
                        externalInstances[port]!!.launch()
                    }

                    bean is TrojanGoBean -> {
                        val configFile = File(
                            cacheDir, "trojan_go_" + SystemClock.elapsedRealtime() + ".json"
                        )
                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = mutableListOf(
                            initPlugin("trojan-go-plugin").path, "-config", configFile.absolutePath
                        )

                        processes.start(commands)
                    }

                    bean is MieruBean -> {
                        val configFile = File(
                            cacheDir, "mieru_" + SystemClock.elapsedRealtime() + ".json"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val envMap = mutableMapOf<String, String>()
                        envMap["MIERU_CONFIG_JSON_FILE"] = configFile.absolutePath
                        envMap["MIERU_PROTECT_PATH"] = "protect_path"

                        val commands = mutableListOf(
                            initPlugin("mieru-plugin").path, "run",
                        )

                        processes.start(commands, envMap)
                    }

                    bean is NaiveBean -> {
                        val configFile = File(
                            cacheDir, "naive_" + SystemClock.elapsedRealtime() + ".json"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val envMap = mutableMapOf<String, String>()

                        if (bean.certificates.isNotBlank()) {
                            val certFile = File(
                                cacheDir, "naive_" + SystemClock.elapsedRealtime() + ".crt"
                            )

                            certFile.parentFile?.mkdirs()
                            certFile.writeText(bean.certificates)
                            cacheFiles.add(certFile)

                            envMap["SSL_CERT_FILE"] = certFile.absolutePath
                        }

                        val commands = mutableListOf(
                            initPlugin("naive-plugin").path, configFile.absolutePath
                        )

                        processes.start(commands, envMap)
                    }

                    bean is HysteriaBean -> {
                        val configFile = File(
                            cacheDir, "hysteria_" + SystemClock.elapsedRealtime() + ".json"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = mutableListOf(
                            initPlugin("hysteria-plugin").path,
                            "--no-check",
                            "--config",
                            configFile.absolutePath,
                            "--log-level",
                            if (DataStore.logLevel > 0) "trace" else "warn",
                            "client"
                        )

                        if (bean.protocol == HysteriaBean.PROTOCOL_FAKETCP) {
                            commands.addAll(0, listOf("su", "-c"))
                        }

                        processes.start(commands)
                    }
                }
            }
        }

        box.start()
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override fun close() {
        for (instance in externalInstances.values) {
            runCatching {
                instance.close()
            }
        }

        cacheFiles.removeAll { it.delete(); true }

        xrayClosed.set(true)
        synchronized(xrayLifecycleLock) {
            xhttpEntryRelay?.close()
            xhttpEntryRelay = null
            xraySidecar?.stop(XrayStopReason.Shutdown)
            xraySidecar = null
            activeXraySpec = null
            activeXrayConfigDir = null
        }

        if (::processes.isInitialized) processes.close()

        if (::box.isInitialized) {
            box.close()
        }
    }

}
