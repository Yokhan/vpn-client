package com.yokhan.gates.windows.service

import com.google.gson.JsonParser
import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.ptr.LongByReference
import com.sun.jna.win32.StdCallLibrary
import com.yokhan.gates.windows.service.wfp.WfpGuardBackend
import com.yokhan.gates.windows.service.wfp.WfpAction
import com.yokhan.gates.windows.service.wfp.WfpPolicy
import com.yokhan.gates.windows.service.wfp.WfpPolicyPlanner
import com.yokhan.gates.windows.service.wfp.WfpScope
import com.yokhan.gates.windows.service.wfp.WindowsFilteringPlatformBackend
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets.UTF_16LE
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

data class SelectedAppProtection(val protectedExecutables: List<String>)

enum class ProtectionStatus {
    DISABLED,
    BLOCKED,
    ARMED,
    ERROR,
}

data class TunIdentity(val interfaceAlias: String, val addresses: Set<String>, val luid: Long)

enum class ProtectionPermitResult {
    PERMITTED,
    IDENTITY_NOT_READY,
}

sealed interface TunIdentityResolution {
    data class Ready(val identity: TunIdentity) : TunIdentityResolution
    data object NotReady : TunIdentityResolution
}

fun interface TunIdentityResolver {
    fun resolve(interfaceAlias: String, configuredAddresses: Set<String>): TunIdentityResolution
}

interface SelectedAppProtectionController : AutoCloseable {
    fun arm(config: ValidatedSingBoxConfig)
    fun permit(
        config: ValidatedSingBoxConfig,
        confirmChildAlive: () -> Unit = {},
    ): ProtectionPermitResult
    fun block()
    fun release()
    fun status(): ProtectionStatus
    fun closePermitsOnly()
}

class WfpSelectedAppProtection(
    private val coreExecutablePath: String,
    private val installationAlias: String,
    private val backendFactory: () -> WfpGuardBackend = WindowsFilteringPlatformBackend::create,
    private val identityResolver: TunIdentityResolver = WindowsTunIdentityResolver,
) : SelectedAppProtectionController {
    private var backend: WfpGuardBackend? = null
    private var currentPolicy: WfpPolicy? = null

    @Synchronized
    override fun arm(config: ValidatedSingBoxConfig) {
        val selected = config.protection ?: return
        val policy = WfpPolicy(
            scope = WfpScope.SelectedExecutables(selected.protectedExecutables),
            bypassAbsoluteExePaths = emptyList(),
            coreAbsoluteExePath = coreExecutablePath,
        )
        backend().armBlocked(policy)
        currentPolicy = policy
    }

    @Synchronized
    override fun permit(config: ValidatedSingBoxConfig, confirmChildAlive: () -> Unit): ProtectionPermitResult {
        if (config.protection == null) {
            confirmChildAlive()
            return ProtectionPermitResult.PERMITTED
        }
        val policy = currentPolicy ?: error("Protection is not armed")
        val expectedAddresses = configuredTunAddresses(config.canonicalJson)
        val resolution = try {
            identityResolver.resolve(installationAlias, expectedAddresses)
        } catch (failure: LinkageError) {
            throw IOException("TUN identity backend is unavailable", failure)
        }
        val identity = when (val result = resolution) {
            TunIdentityResolution.NotReady -> return ProtectionPermitResult.IDENTITY_NOT_READY
            is TunIdentityResolution.Ready -> result.identity
        }
        check(identity.interfaceAlias == installationAlias && identity.luid > 0 &&
            identity.addresses == expectedAddresses) { "Owned TUN identity did not match" }
        check(policy.scope == WfpScope.SelectedExecutables(config.protection.protectedExecutables))
        confirmChildAlive()
        try {
            backend().permitTunnel(identity.luid)
        } catch (failure: LinkageError) {
            throw IOException("Selected-app protection backend is unavailable", failure)
        }
        currentPolicy = policy.copy(verifiedTunLuid = identity.luid)
        return ProtectionPermitResult.PERMITTED
    }

    @Synchronized
    override fun block() {
        if (currentPolicy == null) return
        backend().block()
        currentPolicy = currentPolicy?.copy(verifiedTunLuid = null)
    }

    @Synchronized
    override fun release() {
        val guard = backend()
        guard.disarm()
        currentPolicy = null
    }

    @Synchronized
    override fun status(): ProtectionStatus = try {
        val rules = backend().inspect().rules
        val policy = currentPolicy
        if (policy == null) {
            when {
                rules.any { it.persistent && it.action == WfpAction.BLOCK } -> ProtectionStatus.BLOCKED
                rules.isEmpty() -> ProtectionStatus.DISABLED
                else -> ProtectionStatus.ERROR
            }
        } else {
            val expectedPersistent = WfpPolicyPlanner.plan(policy, includeTunnelPermit = false)
                .filter { it.persistent && it.action == WfpAction.BLOCK }.map { it.key }.toSet()
            val expectedDynamic = WfpPolicyPlanner.plan(policy)
                .filterNot { it.persistent }.map { it.key }.toSet()
            val persistentKeys = rules.filter { it.persistent }.map { it.key }.toSet()
            val dynamicKeys = rules.filterNot { it.persistent }.map { it.key }.toSet()
            when {
                persistentKeys != expectedPersistent -> ProtectionStatus.ERROR
                expectedDynamic.isNotEmpty() && dynamicKeys == expectedDynamic -> ProtectionStatus.ARMED
                expectedDynamic.isEmpty() && dynamicKeys.isEmpty() -> ProtectionStatus.BLOCKED
                else -> ProtectionStatus.ERROR
            }
        }
    } catch (_: Exception) {
        ProtectionStatus.ERROR
    }

    @Synchronized
    override fun closePermitsOnly() {
        backend?.closePermitsOnly()
        if (currentPolicy != null) {
            currentPolicy = currentPolicy?.copy(verifiedTunLuid = null)
        }
    }

    @Synchronized
    override fun close() = closePermitsOnly()

    @Synchronized
    private fun backend(): WfpGuardBackend = backend ?: backendFactory().also { backend = it }
}

object DisabledSelectedAppProtection : SelectedAppProtectionController {
    override fun arm(config: ValidatedSingBoxConfig) = Unit
    override fun permit(config: ValidatedSingBoxConfig, confirmChildAlive: () -> Unit): ProtectionPermitResult {
        confirmChildAlive()
        return ProtectionPermitResult.PERMITTED
    }
    override fun block() = Unit
    override fun release() = Unit
    override fun status() = ProtectionStatus.DISABLED
    override fun closePermitsOnly() = Unit
    override fun close() = Unit
}

object WindowsTunIdentityResolver : TunIdentityResolver {
    private val api by lazy { Native.load("Iphlpapi", InterfaceAliasApi::class.java) }

    override fun resolve(interfaceAlias: String, configuredAddresses: Set<String>): TunIdentityResolution {
        if (!isWindows()) throw IOException("Windows TUN identity backend is unavailable on this platform")
        val powershellFalse = "$" + "false"
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(${powershellFalse})
            ${'$'}alias = '${interfaceAlias.replace("'", "''")}'
            try {
                ${'$'}rows = @(Get-NetIPAddress -InterfaceAlias ${'$'}alias -ErrorAction Stop | Select-Object InterfaceAlias, IPAddress)
            } catch {
                if (${'$'}_.CategoryInfo.Category -eq 'ObjectNotFound') {
                    @{ status = 'not-ready' } | ConvertTo-Json -Compress
                    exit 0
                }
                [Console]::Error.WriteLine('Interface address query failed')
                exit 70
            }
            if (${'$'}rows.Count -eq 0) {
                @{ status = 'not-ready' } | ConvertTo-Json -Compress
                exit 0
            }
            @{ status = 'ready'; alias = ${'$'}rows[0].InterfaceAlias; addresses = @(${'$'}rows | Select-Object -ExpandProperty IPAddress) } | ConvertTo-Json -Compress
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(UTF_16LE))
        val process = ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .redirectErrorStream(true).start()
        val output = ByteArrayOutputStream()
        val readerFailure = java.util.concurrent.atomic.AtomicReference<Exception?>(null)
        val queryDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(IDENTITY_QUERY_TIMEOUT_MILLIS)
        val reader = Thread {
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val remaining = MAX_IDENTITY_OUTPUT_BYTES + 1 - output.size()
                            if (remaining > 0) output.write(buffer, 0, minOf(count, remaining))
                        }
                    }
                }
            } catch (failure: Exception) {
                readerFailure.set(failure)
            }
        }.apply { isDaemon = true; start() }
        try {
            val completed = process.waitFor(IDENTITY_QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            if (!completed) throw IOException("TUN identity query exceeded its bounded subprocess deadline")
            val remainingNanos = queryDeadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) throw IOException("TUN identity output exceeded its bounded subprocess deadline")
            val remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)
            reader.join(remainingMillis)
            if (reader.isAlive) throw IOException("TUN identity output reader exceeded its bounded deadline")
            readerFailure.get()?.let { throw IOException("TUN identity output could not be read", it) }
            val exitCode = process.exitValue()
            if (exitCode != 0) throw IOException("TUN identity backend query failed")
            val raw = synchronized(output) { output.toByteArray() }
            if (raw.size > MAX_IDENTITY_OUTPUT_BYTES) throw IOException("TUN identity backend response exceeded its limit")
            val status = try { JsonParser.parseString(raw.toString(Charsets.UTF_8)).asJsonObject }
            catch (failure: Exception) { throw IOException("TUN identity backend response was invalid", failure) }
            if (status.get("status")?.asString == "not-ready") return TunIdentityResolution.NotReady
            if (status.get("status")?.asString != "ready") throw IOException("TUN identity backend returned an unknown status")
            if (status.get("alias")?.asString != interfaceAlias) return TunIdentityResolution.NotReady
            val values = status.getAsJsonArray("addresses")?.map { it.asString }
                ?: throw IOException("TUN identity backend omitted addresses")
            val expectedNormalized = configuredAddresses.mapNotNull(::normalizeNumericIpAddress).toSet()
            if (expectedNormalized.size != configuredAddresses.size) throw IOException("Configured TUN address set is invalid")
            val observedNormalized = values.mapNotNull(::normalizeNumericIpAddress)
            if (observedNormalized.size != values.size) throw IOException("TUN identity backend returned an invalid address")
            if (!tunAddressesMatch(expectedNormalized, values)) return TunIdentityResolution.NotReady
            val luid = LongByReference()
            val luidResult = api.ConvertInterfaceAliasToLuid(WString(interfaceAlias), luid)
            if (luidResult == ERROR_NOT_FOUND) return TunIdentityResolution.NotReady
            if (luidResult != 0 || luid.value <= 0) throw IOException("TUN interface LUID could not be verified")
            return TunIdentityResolution.Ready(TunIdentity(interfaceAlias, expectedNormalized, luid.value))
        } catch (failure: InterruptedException) {
            val stopped = stopQuery(process, reader)
            Thread.currentThread().interrupt()
            if (!stopped) throw IOException("TUN identity query could not be stopped", failure)
            throw failure
        } catch (failure: Exception) {
            if (!stopQuery(process, reader)) throw IOException("TUN identity query could not be stopped", failure)
            throw failure
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun stopQuery(process: Process, reader: Thread): Boolean {
        process.destroyForcibly()
        val processStopped = runCatching {
            !process.isAlive || process.waitFor(IDENTITY_QUERY_CLEANUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        runCatching { process.inputStream.close() }
        runCatching { reader.join(IDENTITY_QUERY_CLEANUP_TIMEOUT_MILLIS) }
        return processStopped && !process.isAlive && !reader.isAlive
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private interface InterfaceAliasApi : StdCallLibrary {
        fun ConvertInterfaceAliasToLuid(alias: WString, luid: LongByReference): Int
    }
}

fun selectedProtectionAlias(controlToken: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(controlToken.toByteArray(Charsets.US_ASCII))
    return "GATES-${digest.take(12).joinToString("") { "%02x".format(it) }}"
}

internal fun configuredTunAddresses(canonicalJson: String): Set<String> {
    val root = JsonParser.parseString(canonicalJson).asJsonObject
    val tun = root.getAsJsonArray("inbounds").firstOrNull { element ->
        element.isJsonObject && element.asJsonObject.get("type")?.asString == "tun"
    }?.asJsonObject ?: error("TUN inbound is missing")
    val address = tun.get("address") ?: error("TUN addresses are missing")
    val addresses = when {
        address.isJsonArray -> address.asJsonArray.map { it.asString }
        address.isJsonPrimitive && address.asJsonPrimitive.isString -> listOf(address.asString)
        else -> emptyList()
    }.map { address ->
        normalizeNumericIpAddress(address.substringBefore('/')) ?: error("TUN address must be a numeric IP address")
    }.toSet()
    require(addresses.isNotEmpty()) { "TUN addresses are missing" }
    return addresses
}

internal fun tunAddressesMatch(configuredAddresses: Set<String>, observedAddresses: Collection<String>): Boolean {
    if (configuredAddresses.isEmpty() || observedAddresses.isEmpty()) return false
    val normalizedExpected = configuredAddresses.mapNotNull(::normalizeNumericIpAddress).toSet()
    if (normalizedExpected.size != configuredAddresses.size) return false
    val normalizedObserved = observedAddresses.mapNotNull(::normalizeNumericIpAddress)
    if (normalizedObserved.size != observedAddresses.size) return false
    val observed = normalizedObserved.toSet()
    if (!observed.containsAll(normalizedExpected)) return false
    return (observed - normalizedExpected).all(::isIpv6LinkLocal)
}

internal fun normalizeNumericIpAddress(address: String): String? {
    if (Regex("(?i)^4:[0-9a-f]{8}$|^6:[0-9a-f]{32}$").matches(address)) return address.lowercase()
    val value = address.substringBefore('%')
    if (':' !in value) {
        val octets = value.split('.')
        if (octets.size != 4) return null
        val bytes = octets.map { it.toIntOrNull()?.takeIf { octet -> octet in 0..255 } ?: return null }
        return "4:" + bytes.joinToString("") { it.toString(16).padStart(2, '0') }
    }
    if (!Regex("[0-9a-fA-F:.]+").matches(value)) return null
    val bytes = try {
        (InetAddress.getByName(value) as? Inet6Address)?.address ?: return null
    } catch (_: Exception) {
        return null
    }
    return "6:" + bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

private fun isIpv6LinkLocal(normalized: String): Boolean {
    if (!normalized.startsWith("6:")) return false
    val firstByte = normalized.substring(2, 4).toIntOrNull(16) ?: return false
    val secondByte = normalized.substring(4, 6).toIntOrNull(16) ?: return false
    return firstByte == 0xfe && (secondByte and 0xc0) == 0x80
}

internal fun runtimeConfigJson(config: ValidatedSingBoxConfig, alias: String?): String {
    val root = JsonParser.parseString(config.canonicalJson).asJsonObject
    if (alias != null) {
        val tun = root.getAsJsonArray("inbounds").first { it.asJsonObject.get("type")?.asString == "tun" }.asJsonObject
        tun.addProperty("interface_name", alias)
    }
    return com.google.gson.Gson().toJson(root)
}

private const val IDENTITY_QUERY_TIMEOUT_MILLIS = 2_000L
private const val IDENTITY_QUERY_CLEANUP_TIMEOUT_MILLIS = 250L
private const val ERROR_NOT_FOUND = 1168
private const val MAX_IDENTITY_OUTPUT_BYTES = 16 * 1024
