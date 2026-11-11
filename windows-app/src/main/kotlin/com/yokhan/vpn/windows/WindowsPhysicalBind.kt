package com.yokhan.gates.windows

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * PowerShell console output is encoded with the OEM code page, so a friendly
 * interface alias such as "Беспроводная сеть" cannot be passed through
 * verbatim. The detection script base64-encodes the UTF-8 alias instead.
 */
internal fun decodeWindowsInterfaceAlias(value: String): String? {
    if (value.isBlank()) return null
    return runCatching {
        String(Base64.getDecoder().decode(value.trim()), StandardCharsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
