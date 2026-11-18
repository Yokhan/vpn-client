package com.yokhan.gates.windows.service

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.yokhan.gates.windows.service.wfp.WfpGuardBackend
import com.yokhan.gates.windows.service.wfp.WfpAction
import com.yokhan.gates.windows.service.wfp.WfpPolicy
import com.yokhan.gates.windows.service.wfp.WfpPolicyPlanner
import com.yokhan.gates.windows.service.wfp.WfpScope
import com.yokhan.gates.windows.service.wfp.WindowsFilteringPlatformBackend
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest

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
        val luid = LongByReference()
        val luidResult = api.ConvertInterfaceAliasToLuid(WString(interfaceAlias), luid)
        if (isInterfaceNotReadyWin32Error(luidResult)) return TunIdentityResolution.NotReady
        if (luidResult != 0 || luid.value <= 0) throw IOException("TUN interface LUID could not be verified ($luidResult)")

        val aliasBuffer = CharArray(IF_MAX_STRING_SIZE + 1)
        val aliasResult = api.ConvertInterfaceLuidToAlias(luid, aliasBuffer, aliasBuffer.size)
        if (isInterfaceNotReadyWin32Error(aliasResult)) return TunIdentityResolution.NotReady
        if (aliasResult != 0) throw IOException("TUN interface alias could not be verified ($aliasResult)")
        val resolvedAlias = Native.toString(aliasBuffer)
        if (resolvedAlias != interfaceAlias) return TunIdentityResolution.NotReady

        val tableReference = PointerByReference()
        val tableResult = api.GetUnicastIpAddressTable(AF_UNSPEC, tableReference)
        if (tableResult != 0) throw IOException("TUN unicast address table query failed ($tableResult)")
        val tablePointer = tableReference.value ?: return TunIdentityResolution.NotReady
        val values = try {
            extractUnicastAddressesForLuid(tablePointer, luid.value)
        } finally {
            api.FreeMibTable(tablePointer)
        }
        if (values.isEmpty()) return TunIdentityResolution.NotReady

        val expectedNormalized = configuredAddresses.mapNotNull(::normalizeNumericIpAddress).toSet()
        if (expectedNormalized.size != configuredAddresses.size) throw IOException("Configured TUN address set is invalid")
        val observedNormalized = values.mapNotNull(::normalizeNumericIpAddress)
        if (observedNormalized.size != values.size) throw IOException("TUN identity backend returned an invalid address")
        if (!tunAddressesMatch(expectedNormalized, values)) return TunIdentityResolution.NotReady
        return TunIdentityResolution.Ready(TunIdentity(interfaceAlias, expectedNormalized, luid.value))
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private interface InterfaceAliasApi : StdCallLibrary {
        fun ConvertInterfaceAliasToLuid(alias: WString, luid: LongByReference): Int
        fun ConvertInterfaceLuidToAlias(luid: LongByReference, alias: CharArray, length: Int): Int
        fun GetUnicastIpAddressTable(family: Short, table: PointerByReference): Int
        fun FreeMibTable(memory: Pointer)
    }
}

internal fun extractUnicastAddressesForLuid(tablePointer: Pointer, targetLuid: Long): List<String> {
    val count = tablePointer.getInt(0L)
    if (count <= 0) return emptyList()
    val addresses = mutableListOf<String>()
    for (index in 0 until count) {
        val rowOffset = UNICAST_TABLE_HEADER_BYTES + index.toLong() * MIB_UNICASTIPADDRESS_ROW_BYTES
        val rowLuid = tablePointer.getLong(rowOffset + MIB_ROW_LUID_OFFSET)
        if (rowLuid != targetLuid) continue
        val family = tablePointer.getShort(rowOffset).toInt() and 0xffff
        when (family) {
            AF_INET -> {
                val bytes = tablePointer.getByteArray(rowOffset + SOCKADDR_IN_ADDR_OFFSET, 4)
                addresses += InetAddress.getByAddress(bytes).hostAddress
            }
            AF_INET6 -> {
                val bytes = tablePointer.getByteArray(rowOffset + SOCKADDR_IN6_ADDR_OFFSET, 16)
                addresses += InetAddress.getByAddress(bytes).hostAddress
            }
        }
    }
    return addresses
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

internal fun runtimeConfigJson(
    config: ValidatedSingBoxConfig,
    alias: String?,
    transportDirectProcessPaths: List<String> = emptyList(),
): String {
    val root = JsonParser.parseString(config.canonicalJson).asJsonObject
    if (alias != null) {
        val tun = root.getAsJsonArray("inbounds").first { it.asJsonObject.get("type")?.asString == "tun" }.asJsonObject
        tun.addProperty("interface_name", alias)
    }
    if (config.sidecar != null && transportDirectProcessPaths.isNotEmpty()) {
        prependDirectProcessRules(root, transportDirectProcessPaths)
    }
    return com.google.gson.Gson().toJson(root)
}

/**
 * Puts a transport-owned executable ahead of every user rule. sing-box
 * evaluates route rules in order, so the exact path of the XHTTP helper is
 * direct-routed even when the user added a conflicting name-wide or path rule.
 * Exact `process_path` is used instead of a global `process_name`, so only the
 * verified installation is released from the tunnel.
 */
internal fun prependDirectProcessRules(root: JsonObject, processPaths: List<String>) {
    val paths = processPaths.map(String::trim).filter(String::isNotBlank).distinct()
    if (paths.isEmpty()) return
    val route = root.getAsJsonObject("route") ?: JsonObject().also { root.add("route", it) }
    val rules = JsonArray()
    rules.add(JsonObject().apply {
        add("process_path", JsonArray().also { array -> paths.forEach(array::add) })
        addProperty("outbound", "direct")
    })
    route.getAsJsonArray("rules")?.forEach(rules::add)
    route.add("rules", rules)
    route.addProperty("find_process", true)
}

internal fun isInterfaceNotReadyWin32Error(win32Error: Int): Boolean =
    win32Error == ERROR_FILE_NOT_FOUND || win32Error == ERROR_INVALID_PARAMETER || win32Error == ERROR_NOT_FOUND

private const val ERROR_FILE_NOT_FOUND = 2
private const val ERROR_INVALID_PARAMETER = 87
private const val ERROR_NOT_FOUND = 1168
private const val IF_MAX_STRING_SIZE = 256
private const val AF_UNSPEC: Short = 0
private const val AF_INET = 2
private const val AF_INET6 = 23
private const val UNICAST_TABLE_HEADER_BYTES = 8L
private const val MIB_UNICASTIPADDRESS_ROW_BYTES = 80L
private const val MIB_ROW_LUID_OFFSET = 32L
private const val SOCKADDR_IN_ADDR_OFFSET = 4L
private const val SOCKADDR_IN6_ADDR_OFFSET = 8L
