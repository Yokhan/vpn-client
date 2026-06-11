package com.yokhan.gates.app.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import com.yokhan.gates.app.manifest.YokhanRouteEndpoint
import com.yokhan.gates.app.manifest.YokhanRouteManifestParser
import com.yokhan.gates.app.manifest.YokhanRouteManifestStore
import com.yokhan.gates.app.manifest.YokhanRouteManifestUrls
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.math.min
import kotlin.random.Random
import kotlin.system.measureTimeMillis

object YokhanDiagnosticsCollector {

    suspend fun collect(context: Context): YokhanDiagnosticsReport = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        DataStore.ensureGroupsLoaded()
        val currentGroup = DataStore.currentGroup()
        val profiles = SagerDatabase.proxyDao.getByGroup(currentGroup.id)
        val activeProfile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
        val subscriptionGroup = currentGroup.takeIf { it.type == GroupType.SUBSCRIPTION }
            ?: SagerDatabase.groupDao.allGroups().firstOrNull { it.type == GroupType.SUBSCRIPTION }
        val subscriptionLink = subscriptionGroup?.subscription?.link.orEmpty()
        val manifestUrl = YokhanRouteManifestUrls.fromSubscriptionLink(
            subscriptionLink,
            BuildConfig.YOKHAN_ROUTE_MANIFEST_VERSION,
        )
        val network = activeNetwork(appContext)
        val alwaysOnVpnPackage = alwaysOnVpnPackage(appContext)
        val productionPackageName = productionVpnPackageName()

        YokhanDiagnosticsReport(
            capturedAtMillis = System.currentTimeMillis(),
            app = AppDiagnostics(
                packageName = BuildConfig.APPLICATION_ID,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                sdk = Build.VERSION.SDK_INT,
            ),
            runtime = RuntimeDiagnostics(
                serviceState = DataStore.serviceState.name,
                serviceMode = DataStore.serviceMode,
                selectedProxyId = DataStore.selectedProxy,
                selectedGroupId = currentGroup.id,
                mixedPort = DataStore.mixedPort,
                stealthMode = DataStore.stealthMode,
                ruBypassEnabled = DataStore.enableRuBypass,
                network = network,
                bootElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                alwaysOnVpnPackage = alwaysOnVpnPackage,
                alwaysOnVpnLockdown = alwaysOnVpnLockdown(appContext),
                alwaysOnVpnMatchesCurrentPackage = alwaysOnVpnPackage == BuildConfig.APPLICATION_ID,
                alwaysOnVpnMatchesProductionPackage = alwaysOnVpnPackage == productionPackageName,
                batteryOptimizationIgnored = batteryOptimizationIgnored(appContext, BuildConfig.APPLICATION_ID),
                productionBatteryOptimizationIgnored = batteryOptimizationIgnored(appContext, productionPackageName),
            ),
            routes = routeDiagnostics(currentGroup, activeProfile, profiles, subscriptionLink),
            manifest = manifestDiagnostics(appContext, manifestUrl),
            probes = runProbes(appContext, subscriptionLink, network),
            stability = runStabilityProbes(appContext, network),
            sustained = runSustainedProbes(appContext, network),
            udpConnectivity = runUdpConnectivityProbes(network),
        )
    }

    private fun routeDiagnostics(
        currentGroup: ProxyGroup,
        activeProfile: ProxyEntity?,
        profiles: List<ProxyEntity>,
        subscriptionLink: String,
    ): RouteDiagnostics {
        val detectedFamilies = profiles.mapNotNull { it.routeFamily() }
        val distinctFamilies = detectedFamilies.distinct()
        val expectedPresentOrder = EXPECTED_ROUTE_ORDER.filter { it in distinctFamilies }
        return RouteDiagnostics(
            groupName = currentGroup.displayName(),
            groupType = if (currentGroup.type == GroupType.SUBSCRIPTION) "subscription" else "local",
            subscriptionLink = redactUrl(subscriptionLink),
            activeProfile = activeProfile?.safeDisplayName().orEmpty(),
            activeProtocol = activeProfile?.safeDisplayType().orEmpty(),
            profileCount = profiles.size,
            profiles = profiles.map {
                ProfileDiagnostics(
                    id = it.id,
                    name = it.safeDisplayName(),
                    protocol = it.safeDisplayType(),
                    routeFamily = it.routeFamily().orEmpty(),
                    userOrder = it.userOrder,
                    active = it.id == DataStore.selectedProxy,
                )
            },
            activeRouteFamily = activeProfile?.routeFamily().orEmpty(),
            detectedRouteOrder = distinctFamilies,
            missingRouteFamilies = EXPECTED_ROUTE_ORDER.filterNot { it in distinctFamilies },
            duplicateRouteFamilies = detectedFamilies.groupingBy { it }.eachCount()
                .filterValues { it > 1 },
            routeOrderOk = distinctFamilies == expectedPresentOrder,
        )
    }

    private fun manifestDiagnostics(
        context: Context,
        manifestUrl: String?,
    ): ManifestDiagnostics {
        val cachedRaw = manifestUrl?.let { YokhanRouteManifestStore(context).loadRaw(it) }
        val parsed = cachedRaw?.let {
            runCatching { YokhanRouteManifestParser.parse(it) }.getOrNull()
        }
        return ManifestDiagnostics(
            enabled = BuildConfig.YOKHAN_ROUTE_MANIFEST_ENABLED,
            publicKeysConfigured = BuildConfig.YOKHAN_ROUTE_MANIFEST_PUBLIC_KEYS.isNotBlank(),
            requestedVersion = BuildConfig.YOKHAN_ROUTE_MANIFEST_VERSION,
            manifestUrl = redactUrl(manifestUrl.orEmpty()),
            cachePresent = cachedRaw != null,
            cachedVersion = parsed?.version ?: 0,
            cachedManifestId = parsed?.manifestId.orEmpty(),
            cachedChannel = parsed?.channel.orEmpty(),
            cachedExpiresAt = parsed?.expiresAt ?: 0L,
            cachedActiveRoutes = parsed?.routes
                ?.filter { it.enabled && it.appPrimary }
                ?.sortedBy { it.priority }
                ?.map { it.id }
                .orEmpty(),
            policyVersion = parsed?.policy?.version ?: 0,
            policyRouteOrder = parsed?.policy?.routeOrder.orEmpty(),
            ruDirectVersion = parsed?.policy?.ruDirect?.version.orEmpty(),
            rawCompatibilityIsolated = parsed?.policy?.rawCompatibility?.isolated,
            dnsPolicyMode = parsed?.policy?.client?.dns?.mode.orEmpty(),
            dnsForeignResolver = parsed?.policy?.client?.dns?.foreignResolver.orEmpty(),
            dnsRuDirectResolver = parsed?.policy?.client?.dns?.ruDirectResolver.orEmpty(),
            fingerprintMode = parsed?.policy?.client?.fingerprint?.mode.orEmpty(),
            fingerprintDefault = parsed?.policy?.client?.fingerprint?.default.orEmpty(),
            fingerprintAllowed = parsed?.policy?.client?.fingerprint?.allowed.orEmpty(),
            tlsFragmentationMode = parsed?.policy?.client?.tlsFragmentation?.mode.orEmpty(),
            labRoutes = parsed?.policy?.labRoutes
                ?.map { "${it.id}:${it.family}:${if (it.enabled) "enabled" else "disabled"}:${it.channel}" }
                .orEmpty(),
            diagnosticsExpectedExit = parsed?.diagnostics?.expectedExit?.display().orEmpty(),
            diagnosticsProbeTargets = parsed?.diagnostics?.probeTargets.orEmpty(),
            previousKnownGoodEnabled = parsed?.fallback?.previousKnownGood?.enabled
                ?: (parsed?.fallback?.usePreviousKnownGood ?: false),
            cachedRouteEndpoints = parsed?.routes
                ?.sortedBy { it.priority }
                ?.map {
                    ManifestRouteEndpointDiagnostics(
                        id = it.id,
                        policyVersion = it.policyVersion,
                        ingress = it.ingress?.display().orEmpty(),
                        expectedExit = it.expectedExit?.display().orEmpty(),
                        healthStatus = it.health?.status.orEmpty(),
                    )
                }
                .orEmpty(),
        )
    }

    private fun runProbes(
        context: Context,
        subscriptionLink: String,
        network: NetworkDiagnostics,
    ): List<ProbeDiagnostics> {
        if (!tunnelAvailable(network)) {
            return PROBES.map {
                ProbeDiagnostics(
                    id = it.id,
                    name = it.name,
                    category = it.category,
                    url = redactUrl(it.url),
                    ok = false,
                    elapsedMs = 0L,
                    transport = "skipped",
                    error = "VPN is not connected; skipped tunnel probe",
                )
            }
        }

        val probes = buildList {
            addAll(PROBES)
            if (subscriptionLink.isNotBlank()) {
                add(ProbeTarget("subscription", "Subscription", subscriptionLink, "control"))
                YokhanRouteManifestUrls.fromSubscriptionLink(
                    subscriptionLink,
                    BuildConfig.YOKHAN_ROUTE_MANIFEST_VERSION,
                )?.let { add(ProbeTarget("manifest", "Manifest", it, "control")) }
            }
        }

        val proxy = Proxy(
            Proxy.Type.SOCKS,
            InetSocketAddress("127.0.0.1", DataStore.mixedPort),
        )
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = currentActiveNetwork(manager)
        return probes.map { target ->
            val mixedResult = executeProbe(target, transport = "mixed_proxy") {
                URL(target.url).openConnection(proxy) as HttpURLConnection
            }
            if (!mixedResult.localProxyUnavailable()) {
                mixedResult
            } else {
                executeProbe(target, transport = "active_network_after_mixed_proxy_refused") {
                    val url = URL(target.url)
                    (activeNetwork?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
                }
            }
        }
    }

    private fun runStabilityProbes(
        context: Context,
        network: NetworkDiagnostics,
    ): List<StabilityDiagnostics> {
        if (!tunnelAvailable(network)) {
            return STABILITY_PROBES.map {
                StabilityDiagnostics(
                    id = it.id,
                    name = it.name,
                    category = it.category,
                    url = redactUrl(it.url),
                    attempts = 0,
                    successes = 0,
                    failures = 0,
                    successRatePercent = 0,
                    minMs = 0,
                    p50Ms = 0,
                    p95Ms = 0,
                    maxMs = 0,
                    statusCodes = emptyMap(),
                    errorSamples = listOf("VPN is not connected; skipped stability probe"),
                )
            }
        }

        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = currentActiveNetwork(manager)
        return STABILITY_PROBES.map { target ->
            val samples = (1..STABILITY_ATTEMPTS).map {
                executeProbe(
                    target = target,
                    transport = "active_network_stability",
                    connectTimeoutMs = STABILITY_CONNECT_TIMEOUT_MS,
                    readTimeoutMs = STABILITY_READ_TIMEOUT_MS,
                ) {
                    val url = URL(target.url)
                    (activeNetwork?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
                }
            }
            val successfulLatencies = samples.filter { it.ok }.map { it.elapsedMs }
            val statusCodes = samples
                .filter { it.statusCode > 0 }
                .groupingBy { it.statusCode.toString() }
                .eachCount()
            val errors = samples
                .filterNot { it.ok }
                .map { it.error.ifBlank { "HTTP ${it.statusCode}" } }
                .distinct()
                .take(ERROR_SAMPLE_LIMIT)
            StabilityDiagnostics(
                id = target.id,
                name = target.name,
                category = target.category,
                url = redactUrl(target.url),
                attempts = samples.size,
                successes = successfulLatencies.size,
                failures = samples.size - successfulLatencies.size,
                successRatePercent = if (samples.isEmpty()) {
                    0
                } else {
                    (successfulLatencies.size * 100) / samples.size
                },
                minMs = successfulLatencies.minOrNull() ?: 0L,
                p50Ms = percentile(successfulLatencies, 50),
                p95Ms = percentile(successfulLatencies, 95),
                maxMs = successfulLatencies.maxOrNull() ?: 0L,
                statusCodes = statusCodes,
                errorSamples = errors,
            )
        }
    }

    private fun runSustainedProbes(
        context: Context,
        network: NetworkDiagnostics,
    ): List<SustainedDiagnostics> {
        if (!tunnelAvailable(network)) {
            return DOWNLOAD_PROBES.map {
                SustainedDiagnostics(
                    id = it.id,
                    name = it.name,
                    category = it.category,
                    url = redactUrl(it.url),
                    attempts = 0,
                    successes = 0,
                    failures = 0,
                    bytesReadTotal = 0,
                    minKbps = 0,
                    p50Kbps = 0,
                    maxKbps = 0,
                    p95ElapsedMs = 0,
                    p95FirstByteMs = 0,
                    statusCodes = emptyMap(),
                    errorSamples = listOf("VPN is not connected; skipped sustained probe"),
                )
            }
        }

        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = currentActiveNetwork(manager)
        return DOWNLOAD_PROBES.map { target ->
            val samples = (1..DOWNLOAD_ATTEMPTS).map {
                executeDownloadSample(target) {
                    val url = URL(target.url)
                    (activeNetwork?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
                }
            }
            val successful = samples.filter { it.ok }
            val statusCodes = samples
                .filter { it.statusCode > 0 }
                .groupingBy { it.statusCode.toString() }
                .eachCount()
            val errors = samples
                .filterNot { it.ok }
                .map { it.error.ifBlank { "HTTP ${it.statusCode}" } }
                .distinct()
                .take(ERROR_SAMPLE_LIMIT)
            SustainedDiagnostics(
                id = target.id,
                name = target.name,
                category = target.category,
                url = redactUrl(target.url),
                attempts = samples.size,
                successes = successful.size,
                failures = samples.size - successful.size,
                bytesReadTotal = successful.sumOf { it.bytesRead },
                minKbps = successful.minOfOrNull { it.kbps } ?: 0,
                p50Kbps = percentile(successful.map { it.kbps }, 50),
                maxKbps = successful.maxOfOrNull { it.kbps } ?: 0,
                p95ElapsedMs = percentile(successful.map { it.elapsedMs }, 95),
                p95FirstByteMs = percentile(successful.map { it.firstByteMs }, 95),
                statusCodes = statusCodes,
                errorSamples = errors,
            )
        }
    }

    private fun runUdpConnectivityProbes(network: NetworkDiagnostics): List<UdpConnectivityDiagnostics> {
        if (!tunnelAvailable(network)) {
            return UDP_PROBES.map {
                UdpConnectivityDiagnostics(
                    id = it.id,
                    name = it.name,
                    category = it.category,
                    host = it.host,
                    port = it.port,
                    ok = false,
                    elapsedMs = 0,
                    error = "VPN is not connected; skipped UDP probe",
                    note = UDP_PROBE_NOTE,
                )
            }
        }

        return UDP_PROBES.map { target ->
            var error = ""
            val elapsed = measureTimeMillis {
                try {
                    val address = InetAddress.getByName(target.host)
                    DatagramSocket().use { socket ->
                        socket.soTimeout = UDP_PROBE_TIMEOUT_MS
                        socket.connect(address, target.port)
                        val payload = byteArrayOf(0)
                        socket.send(DatagramPacket(payload, payload.size, address, target.port))
                    }
                } catch (e: Exception) {
                    error = e.readableMessage
                }
            }
            UdpConnectivityDiagnostics(
                id = target.id,
                name = target.name,
                category = target.category,
                host = target.host,
                port = target.port,
                ok = error.isBlank(),
                elapsedMs = elapsed,
                error = error,
                note = UDP_PROBE_NOTE,
            )
        }
    }

    private fun executeProbe(
        target: ProbeTarget,
        transport: String,
        connectTimeoutMs: Int = PROBE_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = PROBE_READ_TIMEOUT_MS,
        openConnection: () -> HttpURLConnection,
    ): ProbeDiagnostics {
        var statusCode = 0
        var finalUrl = ""
        var preview = ""
        var error = ""
        val elapsed = measureTimeMillis {
            try {
                val connection = openConnection().apply {
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    useCaches = false
                    setCommonRequestHeaders()
                }
                try {
                    statusCode = connection.responseCode
                    finalUrl = redactUrl(connection.url.toString())
                    val stream = if (statusCode >= 400) {
                        connection.errorStream
                    } else {
                        connection.inputStream
                    }
                    preview = stream?.readPreview(PREVIEW_LIMIT_CHARS).orEmpty()
                    if (statusCode !in 200..399) {
                        error = "HTTP $statusCode"
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                error = e.readableMessage
            }
        }
        return ProbeDiagnostics(
            id = target.id,
            name = target.name,
            category = target.category,
            url = redactUrl(target.url),
            ok = error.isBlank(),
            elapsedMs = elapsed,
            transport = transport,
            statusCode = statusCode,
            error = error,
            finalUrl = finalUrl,
            bodyPreview = preview,
        )
    }

    private fun executeDownloadSample(
        target: DownloadTarget,
        openConnection: () -> HttpURLConnection,
    ): DownloadSample {
        var statusCode = 0
        var error = ""
        var bytesRead = 0L
        var firstByteMs = 0L
        val started = System.nanoTime()
        try {
            val connection = openConnection().apply {
                connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MS
                readTimeout = DOWNLOAD_READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Range", "bytes=0-${target.maxBytes - 1}")
                setCommonRequestHeaders()
            }
            try {
                statusCode = connection.responseCode
                val stream = if (statusCode >= 400) {
                    connection.errorStream
                } else {
                    connection.inputStream
                }
                if (statusCode !in 200..399) {
                    error = "HTTP $statusCode"
                }
                if (stream != null) {
                    bytesRead = stream.readBounded(
                        limitBytes = target.maxBytes,
                        startedNanos = started,
                        onFirstByte = { firstByteMs = it },
                    )
                }
                if (error.isBlank() && bytesRead == 0L) {
                    error = "empty body"
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            error = e.readableMessage
        }
        val elapsedMs = elapsedMillis(started).coerceAtLeast(1L)
        return DownloadSample(
            ok = error.isBlank(),
            elapsedMs = elapsedMs,
            firstByteMs = firstByteMs,
            bytesRead = bytesRead,
            kbps = if (bytesRead > 0) (bytesRead * 8L) / elapsedMs else 0L,
            statusCode = statusCode,
            error = error,
        )
    }

    private fun HttpURLConnection.setCommonRequestHeaders() {
        setRequestProperty("User-Agent", diagnosticUserAgent())
        setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.7,en;q=0.6")
    }

    private fun tunnelAvailable(network: NetworkDiagnostics): Boolean {
        return DataStore.serviceState.connected || network.vpnTransportActive
    }

    private fun ProbeDiagnostics.localProxyUnavailable(): Boolean {
        if (ok) return false
        val value = error.lowercase()
        return "127.0.0.1" in value && (
            "connection refused" in value ||
                "econnrefused" in value ||
                "failed to connect" in value
            )
    }

    private fun InputStream.readPreview(limit: Int): String = use { input ->
        val buffer = ByteArray(limit * 4)
        val bytesRead = input.read(buffer, 0, buffer.size).coerceAtLeast(0)
        String(buffer, 0, bytesRead, StandardCharsets.UTF_8)
            .replace(Regex("\\s+"), " ")
            .take(limit)
    }

    private fun InputStream.readBounded(
        limitBytes: Int,
        startedNanos: Long,
        onFirstByte: (Long) -> Unit,
    ): Long = use { input ->
        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
        var total = 0L
        var firstByteSeen = false
        while (total < limitBytes) {
            val readLimit = min(buffer.size, limitBytes - total.toInt())
            val read = input.read(buffer, 0, readLimit)
            if (read <= 0) break
            if (!firstByteSeen) {
                firstByteSeen = true
                onFirstByte(elapsedMillis(startedNanos))
            }
            total += read
        }
        total
    }

    private fun elapsedMillis(startedNanos: Long): Long {
        return (System.nanoTime() - startedNanos) / 1_000_000L
    }

    private fun percentile(values: List<Long>, percentile: Int): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val index = (((sorted.size - 1) * percentile) / 100).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun diagnosticUserAgent(): String {
        return DIAGNOSTIC_USER_AGENTS[Random.nextInt(DIAGNOSTIC_USER_AGENTS.size)]
    }

    private fun activeNetwork(context: Context): NetworkDiagnostics {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = currentActiveNetwork(manager)?.let { manager.getNetworkCapabilities(it) }
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val transports = buildList {
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("wifi")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("cellular")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("vpn")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ethernet")
        }
        val validated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } else {
            false
        }
        return NetworkDiagnostics(
            transports = transports,
            downstreamKbps = capabilities?.linkDownstreamBandwidthKbps ?: 0,
            upstreamKbps = capabilities?.linkUpstreamBandwidthKbps ?: 0,
            metered = DataStore.meteredNetwork,
            validated = validated,
            vpnTransportActive = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            carrierName = runCatching { telephony?.networkOperatorName.orEmpty() }.getOrDefault(""),
            simOperatorName = runCatching { telephony?.simOperatorName.orEmpty() }.getOrDefault(""),
            networkOperator = runCatching { telephony?.networkOperator.orEmpty() }.getOrDefault(""),
        )
    }

    private fun alwaysOnVpnPackage(context: Context): String {
        return runCatching {
            Settings.Secure.getString(context.contentResolver, "always_on_vpn_app").orEmpty()
        }.getOrDefault("")
    }

    private fun alwaysOnVpnLockdown(context: Context): Boolean {
        return runCatching {
            Settings.Secure.getInt(context.contentResolver, "always_on_vpn_lockdown", 0) == 1
        }.getOrDefault(false)
    }

    private fun batteryOptimizationIgnored(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return runCatching {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(packageName)
        }.getOrDefault(false)
    }

    private fun currentActiveNetwork(manager: ConnectivityManager): Network? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.activeNetwork
        } else {
            null
        }
    }

    private fun productionVpnPackageName(): String {
        return BuildConfig.APPLICATION_ID.removeSuffix(".debug")
    }

    private fun ProxyEntity.routeFamily(): String? {
        val name = safeDisplayName().lowercase()
        val protocol = safeDisplayType().lowercase()
        return when {
            "bridge" in name -> "bridge"
            "relay" in name -> "relay"
            "naive" in name || "naive" in protocol -> "naive"
            "hy2" in name || "hysteria2" in name || "hysteria2" in protocol -> "hy2"
            else -> null
        }
    }

    private fun ProxyEntity.safeDisplayName(): String {
        return runCatching { displayName().orEmpty() }.getOrDefault("")
    }

    private fun ProxyEntity.safeDisplayType(): String {
        return runCatching { displayType() }.getOrDefault("unknown")
    }

    private fun redactUrl(value: String): String {
        if (value.isBlank()) return ""
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return "<redacted>"
        val scheme = uri.scheme ?: return "<redacted>"
        val host = uri.host ?: return "<redacted>"
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val path = uri.path.orEmpty()
        val redactedPath = if (path.contains("/api/sub/")) {
            path.substringBefore("/api/sub/") + "/api/sub/<token>"
        } else {
            path
        }
        return "$scheme://$host$port$redactedPath"
    }

    private fun YokhanRouteEndpoint.display(): String {
        val portPart = if (port > 0) ":$port" else ""
        val rolePart = role.ifBlank { "unknown" }
        val transportPart = transport.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        return "$rolePart $host$portPart$transportPart".trim()
    }

    private data class ProbeTarget(
        val id: String,
        val name: String,
        val url: String,
        val category: String,
    )

    private data class DownloadTarget(
        val id: String,
        val name: String,
        val url: String,
        val category: String,
        val maxBytes: Int,
    )

    private data class DownloadSample(
        val ok: Boolean,
        val elapsedMs: Long,
        val firstByteMs: Long,
        val bytesRead: Long,
        val kbps: Long,
        val statusCode: Int,
        val error: String,
    )

    private data class UdpTarget(
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
        val category: String,
    )

    private val EXPECTED_ROUTE_ORDER = listOf("bridge", "relay", "naive", "hy2")

    private val PROBES = listOf(
        ProbeTarget("exit_ip", "Exit IP", "https://api.ipify.org", "identity"),
        ProbeTarget("gstatic_204", "Baseline 204", "https://www.gstatic.com/generate_204", "baseline"),
        ProbeTarget("youtube_204", "YouTube 204", "https://www.youtube.com/generate_204", "foreign"),
        ProbeTarget("google_ai_studio", "Google AI Studio", "https://aistudio.google.com/", "google_ai"),
        ProbeTarget("gemini_web", "Gemini Web", "https://gemini.google.com/app", "google_ai"),
        ProbeTarget("google_ai_docs", "Google AI docs", "https://ai.google.dev/", "google_ai"),
        ProbeTarget("gemini_api_discovery", "Gemini API discovery", "https://generativelanguage.googleapis.com/\$discovery/rest?version=v1beta", "google_ai"),
        ProbeTarget("telegram_home", "Telegram", "https://telegram.org/", "foreign"),
        ProbeTarget("instagram", "Instagram", "https://www.instagram.com/robots.txt", "foreign"),
        ProbeTarget("deepseek", "DeepSeek", "https://chat.deepseek.com/", "foreign"),
        ProbeTarget("yandex", "Yandex", "https://ya.ru/", "ru_direct"),
        ProbeTarget("vk", "VK", "https://vk.com/", "ru_direct"),
        ProbeTarget("rutube", "Rutube", "https://rutube.ru/", "ru_direct"),
        ProbeTarget("cloak_root", "Cloak edge", "https://cloak.yokhanfitness.ru/", "edge"),
        ProbeTarget("naive_home", "Naive edge", "https://naive.yokhanfitness.ru/", "edge"),
    )

    private val STABILITY_PROBES = listOf(
        ProbeTarget("youtube_204", "YouTube 204 stability", "https://www.youtube.com/generate_204", "foreign_stability"),
        ProbeTarget("telegram_home", "Telegram stability", "https://telegram.org/", "foreign_stability"),
        ProbeTarget("yandex", "Yandex stability", "https://ya.ru/", "ru_direct_stability"),
        ProbeTarget("rutube", "Rutube stability", "https://rutube.ru/", "ru_direct_stability"),
        ProbeTarget("cloak_root", "Cloak edge stability", "https://cloak.yokhanfitness.ru/", "edge_stability"),
        ProbeTarget("naive_home", "Naive edge stability", "https://naive.yokhanfitness.ru/", "edge_stability"),
    )

    private val DOWNLOAD_PROBES = listOf(
        DownloadTarget("youtube_cdn_static", "YouTube CDN static", "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", "foreign_download", 256 * 1024),
        DownloadTarget("telegram_static", "Telegram static", "https://telegram.org/img/t_logo.svg", "foreign_download", 128 * 1024),
        DownloadTarget("rutube_home", "Rutube home", "https://rutube.ru/", "ru_direct_download", 256 * 1024),
        DownloadTarget("vk_home", "VK home", "https://vk.com/", "ru_direct_download", 256 * 1024),
        DownloadTarget("cloak_root", "Cloak edge", "https://cloak.yokhanfitness.ru/", "edge_download", 256 * 1024),
        DownloadTarget("naive_home", "Naive edge", "https://naive.yokhanfitness.ru/", "edge_download", 256 * 1024),
    )

    private val UDP_PROBES = listOf(
        UdpTarget("youtube_udp_443", "YouTube UDP 443 send", "www.youtube.com", 443, "udp_quic_hint"),
        UdpTarget("telegram_udp_443", "Telegram UDP 443 send", "telegram.org", 443, "udp_quic_hint"),
        UdpTarget("cloak_udp_443", "Cloak UDP 443 send", "cloak.yokhanfitness.ru", 443, "udp_quic_hint"),
        UdpTarget("naive_udp_443", "Naive UDP 443 send", "naive.yokhanfitness.ru", 443, "udp_quic_hint"),
    )

    private val DIAGNOSTIC_USER_AGENTS = listOf(
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Android 14; Mobile; rv:126.0) Gecko/126.0 Firefox/126.0",
    )

    private const val PROBE_CONNECT_TIMEOUT_MS = 6_000
    private const val PROBE_READ_TIMEOUT_MS = 6_000
    private const val STABILITY_ATTEMPTS = 3
    private const val STABILITY_CONNECT_TIMEOUT_MS = 4_000
    private const val STABILITY_READ_TIMEOUT_MS = 4_000
    private const val DOWNLOAD_ATTEMPTS = 2
    private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 5_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 6_000
    private const val DOWNLOAD_BUFFER_BYTES = 32 * 1024
    private const val UDP_PROBE_TIMEOUT_MS = 1_000
    private const val ERROR_SAMPLE_LIMIT = 3
    private const val PREVIEW_LIMIT_CHARS = 512
    private const val UDP_PROBE_NOTE = "send-only UDP reachability hint; this is not a full QUIC/H3 handshake proof"
}

data class YokhanDiagnosticsReport(
    val capturedAtMillis: Long,
    val app: AppDiagnostics,
    val runtime: RuntimeDiagnostics,
    val routes: RouteDiagnostics,
    val manifest: ManifestDiagnostics,
    val probes: List<ProbeDiagnostics>,
    val stability: List<StabilityDiagnostics> = emptyList(),
    val sustained: List<SustainedDiagnostics> = emptyList(),
    val udpConnectivity: List<UdpConnectivityDiagnostics> = emptyList(),
) {
    fun summaryText(): String {
        val failedProbes = probes.count { !it.ok }
        val failedStability = stability.count { it.failures > 0 }
        val failedSustained = sustained.count { it.failures > 0 }
        val failedUdp = udpConnectivity.count { !it.ok }
        val activeRoute = routes.activeRouteFamily.ifBlank { routes.activeProtocol.ifBlank { "unknown" } }
        val network = runtime.network.transports.joinToString("+").ifBlank { "unknown" }
        val routeStatus = if (routes.routeOrderOk && routes.missingRouteFamilies.isEmpty()) {
            "РјР°СЂС€СЂСѓС‚С‹ РІ РїРѕСЂСЏРґРєРµ"
        } else {
            "РµСЃС‚СЊ РїСЂРѕР±Р»РµРјР° СЃ РјР°СЂС€СЂСѓС‚Р°РјРё"
        }
        val probeStatus = if (failedProbes == 0) {
            "РїСЂРѕР±С‹ СѓСЃРїРµС€РЅС‹"
        } else {
            "РѕС€РёР±РѕРє РїСЂРѕР±: $failedProbes"
        }
        val stabilityStatus = if (failedStability == 0) {
            "СЃС‚Р°Р±РёР»СЊРЅРѕСЃС‚СЊ Р±РµР· РѕС€РёР±РѕРє"
        } else {
            "РЅРµСЃС‚Р°Р±РёР»СЊРЅС‹С… С†РµР»РµР№: $failedStability"
        }
        val sustainedStatus = if (failedSustained == 0) {
            "Р·Р°РіСЂСѓР·РєРё Р±РµР· РѕС€РёР±РѕРє"
        } else {
            "РѕС€РёР±РѕРє Р·Р°РіСЂСѓР·РєРё: $failedSustained"
        }
        val udpStatus = if (failedUdp == 0) {
            "udp РѕС‚РїСЂР°РІРєР° РѕРє"
        } else {
            "udp РѕС€РёР±РѕРє: $failedUdp"
        }
        return "${routes.activeProfile.ifBlank { "РїСЂРѕС„РёР»СЊ РЅРµ РІС‹Р±СЂР°РЅ" }} В· route=$activeRoute В· net=$network В· $routeStatus В· $probeStatus В· $stabilityStatus В· $sustainedStatus В· $udpStatus"
    }

    fun toJsonString(): String {
        return JSONObject()
            .put("captured_at_millis", capturedAtMillis)
            .put("app", app.toJson())
            .put("runtime", runtime.toJson())
            .put("routes", routes.toJson())
            .put("manifest", manifest.toJson())
            .put("probes", JSONArray(probes.map { it.toJson() }))
            .put("stability", JSONArray(stability.map { it.toJson() }))
            .put("sustained", JSONArray(sustained.map { it.toJson() }))
            .put("udp_connectivity", JSONArray(udpConnectivity.map { it.toJson() }))
            .toString(2)
    }
}

data class AppDiagnostics(
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
    val sdk: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("package", packageName)
        .put("version_name", versionName)
        .put("version_code", versionCode)
        .put("sdk", sdk)
}

data class RuntimeDiagnostics(
    val serviceState: String,
    val serviceMode: String,
    val selectedProxyId: Long,
    val selectedGroupId: Long,
    val mixedPort: Int,
    val stealthMode: Boolean,
    val ruBypassEnabled: Boolean,
    val network: NetworkDiagnostics,
    val bootElapsedRealtimeMs: Long,
    val alwaysOnVpnPackage: String,
    val alwaysOnVpnLockdown: Boolean,
    val alwaysOnVpnMatchesCurrentPackage: Boolean,
    val alwaysOnVpnMatchesProductionPackage: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val productionBatteryOptimizationIgnored: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("service_state", serviceState)
        .put("service_mode", serviceMode)
        .put("selected_proxy_id", selectedProxyId)
        .put("selected_group_id", selectedGroupId)
        .put("mixed_port", mixedPort)
        .put("stealth_mode", stealthMode)
        .put("ru_bypass_enabled", ruBypassEnabled)
        .put("network", network.toJson())
        .put("boot_elapsed_realtime_ms", bootElapsedRealtimeMs)
        .put("always_on_vpn_package", alwaysOnVpnPackage)
        .put("always_on_vpn_lockdown", alwaysOnVpnLockdown)
        .put("always_on_vpn_matches_current_package", alwaysOnVpnMatchesCurrentPackage)
        .put("always_on_vpn_matches_production_package", alwaysOnVpnMatchesProductionPackage)
        .put("battery_optimization_ignored", batteryOptimizationIgnored)
        .put("production_battery_optimization_ignored", productionBatteryOptimizationIgnored)
}

data class NetworkDiagnostics(
    val transports: List<String>,
    val downstreamKbps: Int,
    val upstreamKbps: Int,
    val metered: Boolean,
    val validated: Boolean,
    val vpnTransportActive: Boolean,
    val carrierName: String,
    val simOperatorName: String,
    val networkOperator: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("transports", JSONArray(transports))
        .put("downstream_kbps", downstreamKbps)
        .put("upstream_kbps", upstreamKbps)
        .put("metered", metered)
        .put("validated", validated)
        .put("vpn_transport_active", vpnTransportActive)
        .put("carrier_name", carrierName)
        .put("sim_operator_name", simOperatorName)
        .put("network_operator", networkOperator)
}

data class RouteDiagnostics(
    val groupName: String,
    val groupType: String,
    val subscriptionLink: String,
    val activeProfile: String,
    val activeProtocol: String,
    val profileCount: Int,
    val profiles: List<ProfileDiagnostics>,
    val activeRouteFamily: String,
    val detectedRouteOrder: List<String>,
    val missingRouteFamilies: List<String>,
    val duplicateRouteFamilies: Map<String, Int>,
    val routeOrderOk: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("group_name", groupName)
        .put("group_type", groupType)
        .put("subscription_link", subscriptionLink)
        .put("active_profile", activeProfile)
        .put("active_protocol", activeProtocol)
        .put("profile_count", profileCount)
        .put("profiles", JSONArray(profiles.map { it.toJson() }))
        .put("active_route_family", activeRouteFamily)
        .put("detected_route_order", JSONArray(detectedRouteOrder))
        .put("missing_route_families", JSONArray(missingRouteFamilies))
        .put("duplicate_route_families", JSONObject(duplicateRouteFamilies))
        .put("route_order_ok", routeOrderOk)
}

data class ProfileDiagnostics(
    val id: Long,
    val name: String,
    val protocol: String,
    val routeFamily: String,
    val userOrder: Long,
    val active: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("protocol", protocol)
        .put("route_family", routeFamily)
        .put("user_order", userOrder)
        .put("active", active)
}

data class ManifestDiagnostics(
    val enabled: Boolean,
    val publicKeysConfigured: Boolean,
    val requestedVersion: Int,
    val manifestUrl: String,
    val cachePresent: Boolean,
    val cachedVersion: Int,
    val cachedManifestId: String,
    val cachedChannel: String,
    val cachedExpiresAt: Long,
    val cachedActiveRoutes: List<String>,
    val policyVersion: Int,
    val ruDirectVersion: String,
    val rawCompatibilityIsolated: Boolean?,
    val diagnosticsExpectedExit: String,
    val previousKnownGoodEnabled: Boolean,
    val cachedRouteEndpoints: List<ManifestRouteEndpointDiagnostics>,
    val policyRouteOrder: List<String>,
    val diagnosticsProbeTargets: List<String>,
    val dnsPolicyMode: String,
    val dnsForeignResolver: String,
    val dnsRuDirectResolver: String,
    val fingerprintMode: String,
    val fingerprintDefault: String,
    val fingerprintAllowed: List<String>,
    val tlsFragmentationMode: String,
    val labRoutes: List<String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("public_keys_configured", publicKeysConfigured)
        .put("requested_version", requestedVersion)
        .put("manifest_url", manifestUrl)
        .put("cache_present", cachePresent)
        .put("cached_version", cachedVersion)
        .put("cached_manifest_id", cachedManifestId)
        .put("cached_channel", cachedChannel)
        .put("cached_expires_at", cachedExpiresAt)
        .put("cached_active_routes", JSONArray(cachedActiveRoutes))
        .put("policy_version", policyVersion)
        .put("policy_route_order", JSONArray(policyRouteOrder))
        .put("ru_direct_version", ruDirectVersion)
        .put("raw_compatibility_isolated", rawCompatibilityIsolated ?: JSONObject.NULL)
        .put("dns_policy_mode", dnsPolicyMode)
        .put("dns_foreign_resolver", dnsForeignResolver)
        .put("dns_ru_direct_resolver", dnsRuDirectResolver)
        .put("fingerprint_mode", fingerprintMode)
        .put("fingerprint_default", fingerprintDefault)
        .put("fingerprint_allowed", JSONArray(fingerprintAllowed))
        .put("tls_fragmentation_mode", tlsFragmentationMode)
        .put("lab_routes", JSONArray(labRoutes))
        .put("diagnostics_expected_exit", diagnosticsExpectedExit)
        .put("diagnostics_probe_targets", JSONArray(diagnosticsProbeTargets))
        .put("previous_known_good_enabled", previousKnownGoodEnabled)
        .put("cached_route_endpoints", JSONArray(cachedRouteEndpoints.map { it.toJson() }))
}

data class ManifestRouteEndpointDiagnostics(
    val id: String,
    val policyVersion: Int,
    val ingress: String,
    val expectedExit: String,
    val healthStatus: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("policy_version", policyVersion)
        .put("ingress", ingress)
        .put("expected_exit", expectedExit)
        .put("health_status", healthStatus)
}

data class ProbeDiagnostics(
    val id: String,
    val name: String,
    val category: String,
    val url: String,
    val ok: Boolean,
    val elapsedMs: Long,
    val transport: String = "",
    val statusCode: Int = 0,
    val error: String = "",
    val finalUrl: String = "",
    val bodyPreview: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("category", category)
        .put("url", url)
        .put("ok", ok)
        .put("elapsed_ms", elapsedMs)
        .put("transport", transport)
        .put("status_code", statusCode)
        .put("error", error)
        .put("final_url", finalUrl)
        .put("body_preview", bodyPreview)
}

data class StabilityDiagnostics(
    val id: String,
    val name: String,
    val category: String,
    val url: String,
    val attempts: Int,
    val successes: Int,
    val failures: Int,
    val successRatePercent: Int,
    val minMs: Long,
    val p50Ms: Long,
    val p95Ms: Long,
    val maxMs: Long,
    val statusCodes: Map<String, Int>,
    val errorSamples: List<String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("category", category)
        .put("url", url)
        .put("attempts", attempts)
        .put("successes", successes)
        .put("failures", failures)
        .put("success_rate_percent", successRatePercent)
        .put("min_ms", minMs)
        .put("p50_ms", p50Ms)
        .put("p95_ms", p95Ms)
        .put("max_ms", maxMs)
        .put("status_codes", JSONObject(statusCodes))
        .put("error_samples", JSONArray(errorSamples))
}

data class SustainedDiagnostics(
    val id: String,
    val name: String,
    val category: String,
    val url: String,
    val attempts: Int,
    val successes: Int,
    val failures: Int,
    val bytesReadTotal: Long,
    val minKbps: Long,
    val p50Kbps: Long,
    val maxKbps: Long,
    val p95ElapsedMs: Long,
    val p95FirstByteMs: Long,
    val statusCodes: Map<String, Int>,
    val errorSamples: List<String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("category", category)
        .put("url", url)
        .put("attempts", attempts)
        .put("successes", successes)
        .put("failures", failures)
        .put("bytes_read_total", bytesReadTotal)
        .put("min_kbps", minKbps)
        .put("p50_kbps", p50Kbps)
        .put("max_kbps", maxKbps)
        .put("p95_elapsed_ms", p95ElapsedMs)
        .put("p95_first_byte_ms", p95FirstByteMs)
        .put("status_codes", JSONObject(statusCodes))
        .put("error_samples", JSONArray(errorSamples))
}

data class UdpConnectivityDiagnostics(
    val id: String,
    val name: String,
    val category: String,
    val host: String,
    val port: Int,
    val ok: Boolean,
    val elapsedMs: Long,
    val error: String,
    val note: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("category", category)
        .put("host", host)
        .put("port", port)
        .put("ok", ok)
        .put("elapsed_ms", elapsedMs)
        .put("error", error)
        .put("note", note)
}
