package com.yokhan.gates.windows.service

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object ControlAuthentication {
    fun newNonce(): String = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes).toHex()

    fun serverProof(token: String, clientNonce: String, serverNonce: String): String =
        hmac(token, "server|$clientNonce|$serverNonce")

    fun clientProof(token: String, clientNonce: String, serverNonce: String): String =
        hmac(token, "client|$clientNonce|$serverNonce")

    fun matches(expected: String, actual: String): Boolean = MessageDigest.isEqual(
        expected.toByteArray(Charsets.US_ASCII),
        actual.toByteArray(Charsets.US_ASCII),
    )

    fun hello(clientNonce: String): String = "$CURRENT_PROTOCOL_VERSION|HELLO|$clientNonce"

    fun parseHello(frame: String): String? {
        val parts = frame.split('|')
        return parts.getOrNull(2)?.takeIf {
            parts.size == 3 && parts[0] == CURRENT_PROTOCOL_VERSION.toString() && parts[1] == "HELLO" && NONCE_PATTERN.matches(it)
        }
    }

    fun challenge(serverNonce: String, proof: String): String =
        "$CURRENT_PROTOCOL_VERSION|CHALLENGE|$serverNonce|$proof"

    fun parseChallenge(frame: String): Pair<String, String>? {
        val parts = frame.split('|')
        if (parts.size != 4 || parts[0] != CURRENT_PROTOCOL_VERSION.toString() || parts[1] != "CHALLENGE") return null
        if (!NONCE_PATTERN.matches(parts[2]) || !PROOF_PATTERN.matches(parts[3])) return null
        return parts[2] to parts[3]
    }

    private fun hmac(token: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.US_ASCII)).toHex()
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private const val NONCE_BYTES = 32
private val NONCE_PATTERN = Regex("[0-9a-f]{64}")
private val PROOF_PATTERN = Regex("[0-9a-f]{64}")
