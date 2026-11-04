package com.yokhan.gates.windows

import com.google.gson.JsonParser
import java.net.URI
import java.util.Base64

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

/** Keep a display-only label short, single-line and free of control characters. */
internal fun windowsAccountLabel(value: String?): String? {
    val normalized = value?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotBlank() } ?: return null
    if (normalized.length > 80 || normalized.any { it.isISOControl() }) return null
    return normalized
}

/**
 * Prefer a stable username or email over an arbitrary display name.
 * A display name is only an optional annotation, never the sole identity.
 */
internal fun windowsAccountLabel(preferredUsername: String?, name: String?, email: String?): String? {
    val base = windowsAccountLabel(preferredUsername) ?: windowsAccountLabel(email) ?: return null
    val display = windowsAccountLabel(name)
    val combined = if (display != null && !display.equals(base, ignoreCase = true)) "$base ($display)" else base
    return windowsAccountLabel(combined) ?: base
}

/** Extract `sub` from a JWT payload for a UserInfo subject match; never verify or log it. */
internal fun windowsTokenSubject(token: String?): String? {
    val payload = token?.split('.')?.getOrNull(1) ?: return null
    return runCatching {
        val json = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        JsonParser.parseString(json).asJsonObject.get("sub")?.asString?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
