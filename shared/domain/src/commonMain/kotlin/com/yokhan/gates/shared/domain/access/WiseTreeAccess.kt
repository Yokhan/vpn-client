package com.yokhan.gates.shared.domain.access

/**
 * Local Wise Tree passphrase verification and embedded limited XHTTP bootstrap profile.
 *
 * The passphrase digest is stored instead of plaintext. When direct access to
 * `app.yokhan.xyz` / `cloak.yokhan.xyz` is blocked before login, the client can still
 * verify the passphrase offline, raise a tightly scoped XHTTP tunnel to the pilot edge
 * (`yokhan.xyz` / `yokhanfitness.ru` only, `final: block`), and then confirm the per-device
 * 24-hour cooldown and personal 15-minute token through that working tunnel.
 */
object WiseTreeAccess {
    private const val SALT = "6254e42b3b7645206aefcb6184d6f78e"
    private const val SALTED_DIGEST_HEX =
        "154a507a085a19336a0cf6d0c6c0abc4ba3db7630d432cea5e927f9ab1cb8ba4"

    const val EMBEDDED_SERVER = "46.226.160.43"
    const val EMBEDDED_PORT = 24443
    const val EMBEDDED_UUID = "1846bfb0-157e-4fd7-84d5-cd7c0556a452"
    const val EMBEDDED_SERVER_NAME = "dutchnews.nl"
    const val EMBEDDED_PUBLIC_KEY = "5LAghbisg6_xZpGjWXPGDdnBGrftxZZXk9Z1xV9pQhc"
    const val EMBEDDED_SHORT_ID = "b9258faf2f593e88"
    const val EMBEDDED_PATH = "/assets/4cf8dd46479e4d0afadba6d0"
    const val EMBEDDED_MODE = "auto"
    const val EMBEDDED_FINGERPRINT = "chrome"

    fun verifyCode(code: String): Boolean =
        verifySaltedCode(code = code, salt = SALT, expectedHex = SALTED_DIGEST_HEX)

    fun verifySaltedCode(code: String, salt: String, expectedHex: String): Boolean {
        val normalized = normalizeCode(code)
        if (normalized.isEmpty()) return false
        val actual = sha256Utf8Hex("$salt:$normalized")
        return constantTimeEquals(actual, expectedHex.trim().lowercase())
    }

    fun normalizeCode(code: String): String =
        code.trim().lowercase().replace(Regex("\\s+"), " ")

    fun buildEmbeddedBootstrapProfileJson(autoDetectInterface: Boolean = true): String =
        """{"log":{"level":"warn","timestamp":true},"dns":{"servers":[{"type":"https","tag":"proxy-dns","server":"1.1.1.1","path":"/dns-query","detour":"xhttp"},{"type":"udp","tag":"direct-dns","server":"77.88.8.8"}],"final":"proxy-dns","strategy":"prefer_ipv4"},"inbounds":[{"type":"tun","tag":"tun-in","interface_name":"gates","address":["172.19.0.1/30"],"mtu":1500,"auto_route":true,"strict_route":true,"stack":"mixed"}],"outbounds":[{"type":"vless","tag":"xhttp","server":"$EMBEDDED_SERVER","server_port":$EMBEDDED_PORT,"uuid":"$EMBEDDED_UUID","transport":{"type":"xhttp","path":"$EMBEDDED_PATH","host":"$EMBEDDED_SERVER_NAME","mode":"$EMBEDDED_MODE"},"tls":{"enabled":true,"server_name":"$EMBEDDED_SERVER_NAME","reality":{"enabled":true,"public_key":"$EMBEDDED_PUBLIC_KEY","short_id":"$EMBEDDED_SHORT_ID"},"utls":{"enabled":true,"fingerprint":"$EMBEDDED_FINGERPRINT"}}},{"type":"direct","tag":"direct"},{"type":"block","tag":"block"}],"route":{"rules":[{"action":"sniff","timeout":"1s"},{"protocol":"dns","action":"hijack-dns"},{"ip_is_private":true,"outbound":"direct"},{"domain_suffix":["yokhan.xyz","yokhanfitness.ru"],"outbound":"xhttp"}],"default_domain_resolver":{"server":"direct-dns","strategy":"prefer_ipv4"},"auto_detect_interface":$autoDetectInterface,"final":"block"}}"""

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    internal fun sha256Utf8Hex(input: String): String {
        val bytes = input.encodeToByteArray()
        val padded = padSha256(bytes)
        val h = intArrayOf(
            0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6,
            0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19,
        )
        val w = IntArray(64)
        var offset = 0
        while (offset < padded.size) {
            for (i in 0 until 16) {
                val j = offset + i * 4
                w[i] = ((padded[j].toInt() and 0xff) shl 24) or
                    ((padded[j + 1].toInt() and 0xff) shl 16) or
                    ((padded[j + 2].toInt() and 0xff) shl 8) or
                    (padded[j + 3].toInt() and 0xff)
            }
            for (i in 16 until 64) {
                val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h[0]
            var b = h[1]
            var c = h[2]
            var d = h[3]
            var e = h[4]
            var f = h[5]
            var g = h[6]
            var hh = h[7]
            for (i in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = hh + s1 + ch + K[i] + w[i]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj
                hh = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }
            h[0] += a
            h[1] += b
            h[2] += c
            h[3] += d
            h[4] += e
            h[5] += f
            h[6] += g
            h[7] += hh
            offset += 64
        }
        return buildString(64) {
            for (word in h) {
                for (shift in 28 downTo 0 step 4) {
                    append(HEX_CHARS[(word ushr shift) and 0x0f])
                }
            }
        }
    }

    private fun padSha256(bytes: ByteArray): ByteArray {
        val bitLen = bytes.size.toLong() * 8L
        val zeroCount = (56 - (bytes.size + 1) % 64 + 64) % 64
        val out = ByteArray(bytes.size + 1 + zeroCount + 8)
        bytes.copyInto(out)
        out[bytes.size] = 0x80.toByte()
        val lenStart = out.size - 8
        for (i in 0 until 8) {
            out[lenStart + i] = (bitLen ushr ((7 - i) * 8)).toByte()
        }
        return out
    }

    private const val HEX_CHARS = "0123456789abcdef"

    private val K = intArrayOf(
        0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
        -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
        -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0x0bf1ca7b, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
    )
}
