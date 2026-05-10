package com.yokhan.vpn.shared.domain

object CertificateFingerprint {
    fun normalizeSha256(value: String): String? {
        val normalized = value
            .trim()
            .replace(":", "")
            .replace(" ", "")
            .lowercase()

        return normalized.takeIf { candidate ->
            candidate.length == SHA_256_HEX_LENGTH &&
                candidate.all { char -> char in '0'..'9' || char in 'a'..'f' }
        }
    }

    private const val SHA_256_HEX_LENGTH = 64
}
