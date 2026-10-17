package com.yokhan.gates.windows

import java.net.URI

internal data class WindowsAuthorizationPrompt(val verificationUri: String, val userCode: String)
internal enum class WindowsAuthorizationPhase { Idle, Starting, Waiting, ReceivingProfile }

internal fun validatedWindowsAuthEndpoint(issuer: String, endpoint: String): String {
    val expected = URI(issuer)
    val candidate = URI(endpoint)
    fun port(uri: URI) = if (uri.port == -1) 443 else uri.port
    require(expected.scheme == "https" && !expected.host.isNullOrBlank())
    require(candidate.scheme == "https" && candidate.host.equals(expected.host, ignoreCase = true) &&
        port(candidate) == port(expected) && candidate.rawUserInfo == null && candidate.rawFragment == null)
    val realmPath = expected.path.trimEnd('/')
    val normalized = URI(null, null, candidate.path, null).normalize().path
    require(normalized.startsWith("$realmPath/") && normalized == candidate.path)
    return candidate.toASCIIString()
}
