package com.yokhan.gates.windows

import java.net.URI
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

internal const val WINDOWS_ROOMNET_RETIRED_WEBSOCKET_URL =
    "wss://cloak.yokhanfitness.ru/api/app/roomnet/ws"
internal const val WINDOWS_ROOMNET_MAX_DATAGRAM_BYTES = 65_507
internal const val WINDOWS_ROOMNET_MAX_FLOW_COUNT = 64
internal const val WINDOWS_ROOMNET_MAX_FLOWS_PER_PEER = 8
internal const val WINDOWS_ROOMNET_MAX_LISTENERS = 64
internal const val WINDOWS_ROOMNET_MAX_LISTENERS_PER_PEER = 8
internal const val WINDOWS_ROOMNET_FLOW_TTL_MILLIS = 120_000L
internal const val WINDOWS_ROOMNET_AUTH_RETRY_STREAK_LIMIT = 1
internal const val WINDOWS_ROOMNET_AUTH_RETRY_RESET_MILLIS = 30_000L
private val windowsRoomNetFlowPattern = Regex("[A-Za-z0-9_-]{1,256}")
private val windowsRoomNetAddressPattern = Regex("127\\.77\\.[0-9]{1,3}\\.[0-9]{1,3}")

internal enum class WindowsRoomNetRetryDecision {
    RETRY,
    STOP,
}

internal fun isWindowsRoomNetRoomAddress(address: String): Boolean {
    if (!windowsRoomNetAddressPattern.matches(address)) return false
    val parts = address.split('.')
    val octets = parts.map { it.toIntOrNull() ?: return false }
    return octets.all { it in 0..255 } && parts.indices.all { octets[it].toString() == parts[it] }
}

internal fun areWindowsRoomNetAddressesDistinct(
    selfAddress: String,
    peerAddresses: List<String>,
    localGameAddress: String,
): Boolean {
    val roomAddresses = listOf(selfAddress) + peerAddresses
    return roomAddresses.all(::isWindowsRoomNetRoomAddress) &&
        roomAddresses.distinct().size == roomAddresses.size &&
        roomAddresses.none { it == localGameAddress }
}

internal fun windowsRoomNetPeerSocketAddress(address: String, port: Int): InetSocketAddress? {
    if (!isWindowsRoomNetRoomAddress(address) || port !in 1..65535) return null
    val octets = address.split('.').map { it.toInt().toByte() }.toByteArray()
    return InetSocketAddress(InetAddress.getByAddress(octets), port)
}

internal fun canCreateWindowsRoomNetFlow(
    peerId: String,
    peerFlowCount: Int,
    totalFlowCount: Int,
): Boolean = peerId.isNotBlank() &&
    peerFlowCount < WINDOWS_ROOMNET_MAX_FLOWS_PER_PEER &&
    totalFlowCount < WINDOWS_ROOMNET_MAX_FLOW_COUNT

internal data class WindowsRoomNetDatagram(
    val room: String,
    val to: String,
    val from: String,
    val destinationPort: Int,
    val flow: String,
    val encodedPayload: String,
)

internal fun canonicalWindowsRoomNetWebSocketUrl(
    appApiBaseUrl: String,
    advertisedUrl: String,
    device: String,
    room: String,
): String {
    val base = URI(appApiBaseUrl)
    require(base.isAbsolute && base.scheme.equals("https", ignoreCase = true))
    require(!base.host.isNullOrBlank() && base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null)
    require(base.rawPath.orEmpty().split('/').none { it == "." || it == ".." })

    val pathPrefix = base.path.orEmpty().trimEnd('/')
    val expectedPath = "$pathPrefix/api/app/roomnet/ws"
    val canonical = URI("wss", null, base.host, base.port, expectedPath, null, null)
    val candidateText = if (advertisedUrl == WINDOWS_ROOMNET_RETIRED_WEBSOCKET_URL) {
        canonical.toASCIIString()
    } else {
        advertisedUrl.ifBlank { canonical.toASCIIString() }
    }
    val candidate = URI(candidateText)
    require(candidate.isAbsolute && candidate.scheme.equals("wss", ignoreCase = true))
    require(candidate.host.equals(base.host, ignoreCase = true) && candidate.port == base.port)
    require(candidate.rawUserInfo == null && candidate.rawFragment == null && candidate.rawQuery == null)
    require(candidate.path == expectedPath)

    val query = buildList {
        add("device=${encodeRoomNetQueryValue(device)}")
        if (room.isNotBlank()) add("room=${encodeRoomNetQueryValue(room)}")
    }.joinToString("&")
    return "${canonical.toASCIIString()}?$query"
}

internal fun encodeRoomNetQueryValue(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

internal fun windowsRoomNetAllowedPorts(roomPorts: List<Int>, peerPorts: List<Int>): Set<Int> {
    val roomAllowed = roomPorts.filter { it in 1..65535 }.toSet()
    if (roomAllowed.isEmpty()) return emptySet()
    val peerAllowed = peerPorts.filter { it in 1..65535 }.toSet()
    return if (peerAllowed.isEmpty()) roomAllowed else roomAllowed intersect peerAllowed
}

internal fun validateWindowsRoomNetDatagram(
    datagram: WindowsRoomNetDatagram,
    room: String,
    selfPeerId: String,
    knownPeerIds: Set<String>,
    allowedPorts: Set<Int>,
): ByteArray? {
    if (datagram.room != room || datagram.to != selfPeerId || datagram.from == selfPeerId) return null
    if (datagram.from !in knownPeerIds || datagram.destinationPort !in allowedPorts) return null
    if (!windowsRoomNetFlowPattern.matches(datagram.flow)) return null
    if (datagram.encodedPayload.length > ((WINDOWS_ROOMNET_MAX_DATAGRAM_BYTES + 2) / 3) * 4) return null
    val payload = runCatching { Base64.getDecoder().decode(datagram.encodedPayload) }.getOrNull() ?: return null
    if (Base64.getEncoder().encodeToString(payload) != datagram.encodedPayload) return null
    return payload.takeIf { it.size <= WINDOWS_ROOMNET_MAX_DATAGRAM_BYTES }
}

internal fun windowsRoomNetFlowExpiresAt(lastActivityMillis: Long): Long =
    lastActivityMillis + WINDOWS_ROOMNET_FLOW_TTL_MILLIS

internal fun isWindowsRoomNetFlowExpired(lastActivityMillis: Long, nowMillis: Long): Boolean =
    nowMillis >= windowsRoomNetFlowExpiresAt(lastActivityMillis)

internal fun decideWindowsRoomNetRetry(
    statusCode: Int?,
    reason: String,
    currentToken: String,
    nextToken: String?,
    stopped: Boolean = false,
    consecutiveAuthRetries: Int = 0,
): WindowsRoomNetRetryDecision {
    if (stopped || statusCode == 403) return WindowsRoomNetRetryDecision.STOP
    if (statusCode == 401) {
        return if (consecutiveAuthRetries < WINDOWS_ROOMNET_AUTH_RETRY_STREAK_LIMIT &&
            !nextToken.isNullOrBlank() && nextToken != currentToken
        ) {
            WindowsRoomNetRetryDecision.RETRY
        } else {
            WindowsRoomNetRetryDecision.STOP
        }
    }
    if (statusCode == 1008) {
        val expiredSession = reason.trim().equals("session expired", ignoreCase = true)
        return if (expiredSession && consecutiveAuthRetries < WINDOWS_ROOMNET_AUTH_RETRY_STREAK_LIMIT &&
            !nextToken.isNullOrBlank() && nextToken != currentToken
        ) {
            WindowsRoomNetRetryDecision.RETRY
        } else {
            WindowsRoomNetRetryDecision.STOP
        }
    }
    return WindowsRoomNetRetryDecision.RETRY
}

internal fun windowsRoomNetReconnectDelayMillis(failureCount: Int): Long {
    val exponent = (failureCount.coerceAtLeast(1) - 1).coerceAtMost(5)
    return (1_000L * (1L shl exponent)).coerceAtMost(30_000L)
}

internal fun isWindowsRoomNetResponseFlowMatch(
    expectedPeerId: String,
    expectedPort: Int,
    incomingPeerId: String,
    incomingPort: Int,
): Boolean = expectedPeerId == incomingPeerId && expectedPort == incomingPort

internal fun <T : Any> putBoundedWindowsRoomNetFlow(
    flows: MutableMap<String, T>,
    key: String,
    value: T,
    nowMillis: Long,
    expiresAt: (T) -> Long,
) {
    synchronized(flows) {
        flows.entries.removeIf { it.value.let(expiresAt) <= nowMillis }
        if (key !in flows && flows.size >= WINDOWS_ROOMNET_MAX_FLOW_COUNT) {
            val oldest = flows.minByOrNull { it.value.let(expiresAt) }?.key ?: return
            flows.remove(oldest)
        }
        flows[key] = value
    }
}
