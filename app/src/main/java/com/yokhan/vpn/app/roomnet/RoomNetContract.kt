package com.yokhan.gates.app.roomnet

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.util.concurrent.atomic.AtomicLong

internal const val ROOMNET_MAX_DATAGRAM_BYTES = 65_507
internal const val ROOMNET_MAX_FLOW_COUNT = 64
internal const val ROOMNET_MAX_FLOWS_PER_PEER = 8
internal const val ROOMNET_MAX_LISTENERS = 64
internal const val ROOMNET_MAX_LISTENERS_PER_PEER = 8
internal const val ROOMNET_FLOW_TTL_MILLIS = 120_000L
internal const val ROOMNET_AUTH_RETRY_STREAK_LIMIT = 1
internal const val ROOMNET_AUTH_RETRY_RESET_MILLIS = 30_000L
internal const val ROOMNET_LOCAL_GAME_HOST = "127.0.0.1"

internal data class RoomNetPeer(
    val id: String = "",
    val username: String = "",
    val device: String = "",
    val address: String = "",
    val ports: List<Int> = emptyList(),
    val online: Boolean = false,
)

internal data class RoomNetRelayInfo(
    val url: String = "",
    val path: String = "/api/app/roomnet/ws",
    val protocol: String = "gates-roomnet-v1",
)

internal data class RoomNetInfo(
    val enabled: Boolean = false,
    val room: String = "",
    val mode: String = "",
    val network: String = "",
    val ports: List<Int> = emptyList(),
    val self: RoomNetPeer? = null,
    val peers: List<RoomNetPeer> = emptyList(),
    val relay: RoomNetRelayInfo = RoomNetRelayInfo(),
    val connected: List<String> = emptyList(),
)

internal data class RoomNetDatagram(
    val room: String,
    val to: String,
    val from: String,
    val destinationPort: Int,
    val flow: String,
    val encodedPayload: String,
)

internal enum class RoomNetRetryDecision { RETRY, STOP }

internal class RoomNetSessionGeneration {
    private val value = AtomicLong()

    fun next(): Long = value.incrementAndGet()

    fun isCurrent(generation: Long): Boolean = value.get() == generation
}

private val roomNetFlowPattern = Regex("[A-Za-z0-9_-]{1,256}")
private val roomNetAddressPattern = Regex("127\\.77\\.[0-9]{1,3}\\.[0-9]{1,3}")

internal fun isRoomNetPeerAddress(address: String): Boolean {
    if (!roomNetAddressPattern.matches(address)) return false
    val parts = address.split('.')
    val octets = parts.map { it.toIntOrNull() ?: return false }
    return octets.all { it in 0..255 } && parts.indices.all { octets[it].toString() == parts[it] }
}

internal fun areRoomNetAddressesDistinct(
    selfAddress: String,
    peerAddresses: List<String>,
): Boolean {
    val addresses = listOf(selfAddress) + peerAddresses
    return addresses.all(::isRoomNetPeerAddress) &&
        addresses.distinct().size == addresses.size &&
        addresses.none { it == ROOMNET_LOCAL_GAME_HOST }
}

internal fun roomNetPeerSocketAddress(address: String, port: Int): InetSocketAddress? {
    if (!isRoomNetPeerAddress(address) || port !in 1..65535) return null
    val octets = address.split('.').map { it.toInt().toByte() }.toByteArray()
    return InetSocketAddress(InetAddress.getByAddress(octets), port)
}

internal fun roomNetAllowedPorts(roomPorts: List<Int>, peerPorts: List<Int>): Set<Int> {
    val roomAllowed = roomPorts.filter { it in 1..65535 }.toSet()
    if (roomAllowed.isEmpty()) return emptySet()
    val peerAllowed = peerPorts.filter { it in 1..65535 }.toSet()
    return if (peerAllowed.isEmpty()) roomAllowed else roomAllowed intersect peerAllowed
}

internal fun canCreateRoomNetFlow(peerId: String, peerFlowCount: Int, totalFlowCount: Int): Boolean =
    peerId.isNotBlank() && peerFlowCount < ROOMNET_MAX_FLOWS_PER_PEER && totalFlowCount < ROOMNET_MAX_FLOW_COUNT

internal fun validateRoomNetDatagram(
    datagram: RoomNetDatagram,
    room: String,
    selfPeerId: String,
    knownPeerIds: Set<String>,
    allowedPorts: Set<Int>,
): ByteArray? {
    if (datagram.room != room || datagram.to != selfPeerId || datagram.from == selfPeerId) return null
    if (datagram.from !in knownPeerIds || datagram.destinationPort !in allowedPorts) return null
    if (!roomNetFlowPattern.matches(datagram.flow)) return null
    if (datagram.encodedPayload.length > ((ROOMNET_MAX_DATAGRAM_BYTES + 2) / 3) * 4) return null
    val payload = datagram.encodedPayload.decodeBase64()?.toByteArray() ?: return null
    if (payload.toByteString().base64() != datagram.encodedPayload) return null
    return payload.takeIf { it.size <= ROOMNET_MAX_DATAGRAM_BYTES }
}

internal fun canonicalRoomNetWebSocketUrl(
    appApiBaseUrl: String,
    advertisedUrl: String,
    device: String,
    room: String,
): String {
    val base = URI(appApiBaseUrl)
    require(base.isAbsolute && base.scheme.equals("https", ignoreCase = true))
    require(!base.host.isNullOrBlank() && base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null)
    require(base.rawPath.orEmpty().split('/').none { it == "." || it == ".." })
    require(base.path.orEmpty().split('/').none { it == "." || it == ".." })

    val pathPrefix = base.path.orEmpty().trimEnd('/')
    val expectedPath = "$pathPrefix/api/app/roomnet/ws"
    val canonical = URI("wss", null, base.host, base.port, expectedPath, null, null)
    val candidateText = advertisedUrl.ifBlank { canonical.toASCIIString() }
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

internal fun roomNetApiUrl(appApiBaseUrl: String): String {
    val base = URI(appApiBaseUrl)
    require(base.isAbsolute && base.scheme.equals("https", ignoreCase = true))
    require(!base.host.isNullOrBlank() && base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null)
    require(base.rawPath.orEmpty().split('/').none { it == "." || it == ".." })
    require(base.path.orEmpty().split('/').none { it == "." || it == ".." })
    return "${base.toASCIIString().trimEnd('/')}/api/app/roomnet"
}

internal fun encodeRoomNetQueryValue(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

internal fun roomNetFlowExpiresAt(lastActivityMillis: Long): Long = lastActivityMillis + ROOMNET_FLOW_TTL_MILLIS

internal fun isRoomNetFlowExpired(lastActivityMillis: Long, nowMillis: Long): Boolean =
    nowMillis >= roomNetFlowExpiresAt(lastActivityMillis)

internal fun decideRoomNetRetry(
    statusCode: Int?,
    reason: String,
    currentToken: String,
    nextToken: String?,
    stopped: Boolean = false,
    consecutiveAuthRetries: Int = 0,
): RoomNetRetryDecision {
    if (stopped || statusCode == 403) return RoomNetRetryDecision.STOP
    if (statusCode == 401 || statusCode == 1008) {
        val expiredSession = statusCode == 401 || reason.trim().equals("session expired", ignoreCase = true)
        return if (expiredSession && consecutiveAuthRetries < ROOMNET_AUTH_RETRY_STREAK_LIMIT &&
            !nextToken.isNullOrBlank() && nextToken != currentToken
        ) RoomNetRetryDecision.RETRY else RoomNetRetryDecision.STOP
    }
    return RoomNetRetryDecision.RETRY
}

internal fun roomNetReconnectDelayMillis(failureCount: Int): Long {
    val exponent = (failureCount.coerceAtLeast(1) - 1).coerceAtMost(5)
    return (1_000L * (1L shl exponent)).coerceAtMost(30_000L)
}

internal fun roomNetResponseFlowMatches(
    expectedPeerId: String,
    expectedPort: Int,
    incomingPeerId: String,
    incomingPort: Int,
): Boolean = expectedPeerId == incomingPeerId && expectedPort == incomingPort

