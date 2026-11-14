package com.yokhan.gates.windows.service

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

private val ALLOWED_TOP_LEVEL_SECTIONS = setOf("log", "dns", "ntp", "inbounds", "outbounds", "route")
private val ALLOWED_OUTBOUND_TYPES = setOf("vless", "hysteria2", "naive", "socks", "direct", "block", "urltest", "selector")
private val REMOTE_OUTBOUND_TYPES = setOf("vless", "hysteria2", "naive")
private const val LOOPBACK_SOCKS_SERVER = "127.0.0.1"
private val FORBIDDEN_KEYS = setOf(
    "file",
    "output",
    "certificate_path",
    "key_path",
    "client_certificate_path",
    "client_key_path",
    "command",
    "executable",
)
private val ALLOWED_INBOUND_TYPES = setOf("tun", "mixed", "http")
private const val TYPE_KEY = "type"
private const val TAG_KEY = "tag"
private const val SERVER_PORT_KEY = "server_port"

data class ValidatedSingBoxConfig(
    val canonicalJson: String,
    val protection: SelectedAppProtection? = null,
    val sidecar: XhttpSidecarSpec? = null,
)

enum class ConfigErrorCode {
    CONFIG_TOO_LARGE,
    MALFORMED_JSON,
    DUPLICATE_JSON_KEY,
    ROOT_NOT_OBJECT,
    UNSUPPORTED_TOP_LEVEL_SECTION,
    EXPERIMENTAL_NOT_ALLOWED,
    FORBIDDEN_KEY,
    MISSING_INBOUNDS,
    INVALID_INBOUNDS,
    TUN_INBOUND_REQUIRED,
    UNSUPPORTED_INBOUND_TYPE,
    MISSING_OUTBOUNDS,
    INVALID_OUTBOUNDS,
    UNSUPPORTED_OUTBOUND_TYPE,
    INVALID_TAG,
    DUPLICATE_TAG,
    MISSING_SERVER_PORT,
    INVALID_SERVER_PORT,
    INVALID_PROTECTION_METADATA,
    INVALID_SOCKS_OUTBOUND,
    INVALID_XHTTP_SIDECAR,
    XHTTP_PHYSICAL_BIND_REQUIRED,
}

data class ConfigValidationError(
    val code: ConfigErrorCode,
    val message: String,
)

sealed interface ConfigValidationResult {
    data class Accepted(val config: ValidatedSingBoxConfig) : ConfigValidationResult
    data class Rejected(val error: ConfigValidationError) : ConfigValidationResult
}

sealed interface ProtectionPayloadResult {
    data class Accepted(val protection: SelectedAppProtection) : ProtectionPayloadResult
    data class Rejected(val error: ConfigValidationError) : ProtectionPayloadResult
}

object SingBoxConfigValidator {
    private val gson = Gson()

    fun validate(
        configBytes: ByteArray,
        coreExecutablePath: String? = null,
        reservedExecutablePaths: Set<String>? = emptySet(),
    ): ConfigValidationResult {
        if (configBytes.size > MAX_CONFIG_BYTES) return reject(ConfigErrorCode.CONFIG_TOO_LARGE, "Config exceeds 256 KiB")
        val root = try {
            parseStrictJson(decodeUtf8(configBytes))
        } catch (error: StrictJsonException) {
            return reject(error.code, error.message)
        } catch (_: Exception) {
            return reject(ConfigErrorCode.MALFORMED_JSON, "Invalid JSON")
        }
        if (!root.isJsonObject) return reject(ConfigErrorCode.ROOT_NOT_OBJECT, "Config root must be an object")
        return validateRoot(root.asJsonObject, coreExecutablePath, reservedExecutablePaths)
    }

    fun validateProtectionPayload(
        payloadBytes: ByteArray,
        coreExecutablePath: String? = null,
        reservedExecutablePaths: Set<String>? = emptySet(),
    ): ProtectionPayloadResult {
        if (payloadBytes.size > MAX_CONFIG_BYTES) {
            return ProtectionPayloadResult.Rejected(error(ConfigErrorCode.CONFIG_TOO_LARGE, "Protection payload exceeds 256 KiB"))
        }
        val parsed = try {
            parseStrictJson(decodeUtf8(payloadBytes))
        } catch (error: StrictJsonException) {
            return ProtectionPayloadResult.Rejected(error(error.code, error.message))
        } catch (_: Exception) {
            return ProtectionPayloadResult.Rejected(error(ConfigErrorCode.MALFORMED_JSON, "Invalid JSON"))
        }
        if (!parsed.isJsonObject) {
            return ProtectionPayloadResult.Rejected(error(ConfigErrorCode.ROOT_NOT_OBJECT, "Protection root must be an object"))
        }
        val obj = parsed.asJsonObject
        val wrapper = when (obj.keySet()) {
            setOf("gates_protection") -> obj
            setOf("schema", "protected_executables") -> JsonObject().apply { add("gates_protection", obj) }
            else -> return ProtectionPayloadResult.Rejected(invalidProtection().error)
        }
        return when (val result = parseProtection(wrapper, coreExecutablePath, reservedExecutablePaths)) {
            is ProtectionParse.Accepted -> {
                val protection = result.protection
                    ?: return ProtectionPayloadResult.Rejected(invalidProtection().error)
                ProtectionPayloadResult.Accepted(protection)
            }
            is ProtectionParse.Rejected -> ProtectionPayloadResult.Rejected(result.error)
        }
    }

    private fun validateRoot(
        root: JsonObject,
        coreExecutablePath: String?,
        reservedExecutablePaths: Set<String>?,
    ): ConfigValidationResult {
        val protection = when (val result = parseProtection(root, coreExecutablePath, reservedExecutablePaths)) {
            is ProtectionParse.Accepted -> result.protection
            is ProtectionParse.Rejected -> return ConfigValidationResult.Rejected(result.error)
        }
        root.remove("gates_protection")
        val sidecar = when (val result = XhttpSidecarSpecParser.parse(root)) {
            is XhttpSidecarParseResult.Accepted -> result.spec
            is XhttpSidecarParseResult.Rejected -> return ConfigValidationResult.Rejected(result.error)
        }
        root.remove("gates_xhttp")
        if (sidecar == null) {
            val outbounds = root.getAsJsonArray("outbounds")
            val hasSocks = outbounds?.any { outbound ->
                outbound.isJsonObject && primitiveString(outbound.asJsonObject.get(TYPE_KEY)) == "socks"
            } == true
            if (hasSocks) {
                return ConfigValidationResult.Rejected(
                    error(ConfigErrorCode.INVALID_SOCKS_OUTBOUND, "SOCKS outbounds require XHTTP sidecar metadata"),
                )
            }
        }
        val topLevelError = validateTopLevelSections(root) ?: validateTree(root, mutableSetOf()) ?: validateInbounds(root) ?: validateOutbounds(root)
        if (topLevelError != null) return ConfigValidationResult.Rejected(topLevelError)
        return ConfigValidationResult.Accepted(ValidatedSingBoxConfig(gson.toJson(root), protection, sidecar))
    }

    private fun parseProtection(
        root: JsonObject,
        coreExecutablePath: String?,
        reservedExecutablePaths: Set<String>?,
    ): ProtectionParse {
        if (!root.has("gates_protection")) return ProtectionParse.Accepted(null)
        val value = root.get("gates_protection")
        if (value == null || !value.isJsonObject) return invalidProtection()
        if (reservedExecutablePaths == null) return invalidProtection()
        val metadata = value.asJsonObject
        if (metadata.keySet() != setOf("schema", "protected_executables")) return invalidProtection()
        val schema = metadata.get("schema")
        if (schema == null || !schema.isJsonPrimitive || !schema.asJsonPrimitive.isNumber ||
            runCatching { schema.asBigDecimal.scale() <= 0 && schema.asBigDecimal.compareTo(BigDecimal.ONE) == 0 }
                .getOrDefault(false).not()) return invalidProtection()
        val executables = metadata.get("protected_executables")
        if (executables == null || !executables.isJsonArray || executables.asJsonArray.size() == 0 || executables.asJsonArray.size() > 128) {
            return invalidProtection()
        }
        val paths = mutableListOf<String>()
        executables.asJsonArray.forEach { item ->
            if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString) return invalidProtection()
            val path = item.asString
            if (!isAbsoluteLocalWindowsExe(path)) return invalidProtection()
            if (coreExecutablePath != null && sameWindowsPath(path, coreExecutablePath)) {
                return invalidProtection()
            }
            if (reservedExecutablePaths.any { sameWindowsPath(path, it) }) return invalidProtection()
            if (paths.any { it.equals(path, ignoreCase = true) }) return invalidProtection()
            paths += path
        }
        return ProtectionParse.Accepted(SelectedAppProtection(paths))
    }

    private fun isAbsoluteLocalWindowsExe(path: String): Boolean =
        Regex("^[A-Za-z]:\\\\").containsMatchIn(path) &&
            path.none { it.code < 0x20 || it in "<>\"|?*" } &&
            path.split('\\').drop(1).none { component ->
                component == "." || component == ".." || ':' in component ||
                    Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$").matches(component)
            } &&
            path.substringAfterLast('\\').endsWith(".exe", ignoreCase = true)

    private fun sameWindowsPath(left: String, right: String): Boolean =
        left.replace('/', '\\').trimEnd('\\').equals(right.replace('/', '\\').trimEnd('\\'), ignoreCase = true)

    private fun invalidProtection() = ProtectionParse.Rejected(
        ConfigValidationError(ConfigErrorCode.INVALID_PROTECTION_METADATA, "gates_protection is invalid"),
    )

    private sealed interface ProtectionParse {
        data class Accepted(val protection: SelectedAppProtection?) : ProtectionParse
        data class Rejected(val error: ConfigValidationError) : ProtectionParse
    }

    private fun validateTopLevelSections(root: JsonObject): ConfigValidationError? {
        root.keySet().forEach { key ->
            if (key == "experimental") return error(ConfigErrorCode.EXPERIMENTAL_NOT_ALLOWED, "experimental is not allowed")
            if (key !in ALLOWED_TOP_LEVEL_SECTIONS) {
                return error(ConfigErrorCode.UNSUPPORTED_TOP_LEVEL_SECTION, "Top-level section '$key' is not allowed")
            }
        }
        return null
    }

    private fun validateTree(element: JsonElement, tags: MutableSet<String>): ConfigValidationError? = when {
        element.isJsonObject -> validateObjectTree(element.asJsonObject, tags)
        element.isJsonArray -> element.asJsonArray.firstNotNullOfOrNull { child -> validateTree(child, tags) }
        else -> null
    }

    private fun validateObjectTree(value: JsonObject, tags: MutableSet<String>): ConfigValidationError? {
        value.entrySet().forEach { (key, child) ->
            if (key.lowercase() in FORBIDDEN_KEYS) {
                return error(ConfigErrorCode.FORBIDDEN_KEY, "Key '$key' is not allowed")
            }
            if (key.equals(SERVER_PORT_KEY, ignoreCase = true) && !isValidPort(child)) {
                return error(ConfigErrorCode.INVALID_SERVER_PORT, "server_port must be an integer from 1 to 65535")
            }
            if (key.equals(TAG_KEY, ignoreCase = true)) {
                val tag = primitiveString(child) ?: return error(ConfigErrorCode.INVALID_TAG, "tag must be a string")
                if (tag.isBlank()) return error(ConfigErrorCode.INVALID_TAG, "tag must not be blank")
                if (!tags.add(tag)) return error(ConfigErrorCode.DUPLICATE_TAG, "Tag '$tag' is duplicated")
            }
            val nestedError = validateTree(child, tags)
            if (nestedError != null) return nestedError
        }
        return null
    }

    private fun validateInbounds(root: JsonObject): ConfigValidationError? {
        val inbounds = root.get("inbounds") ?: return error(ConfigErrorCode.MISSING_INBOUNDS, "inbounds is required")
        if (!inbounds.isJsonArray || inbounds.asJsonArray.size() == 0) {
            return error(ConfigErrorCode.INVALID_INBOUNDS, "inbounds must be a non-empty array")
        }
        val inboundTypes = inbounds.asJsonArray.mapNotNull { inbound ->
            if (!inbound.isJsonObject) return error(ConfigErrorCode.INVALID_INBOUNDS, "Each inbound must be an object")
            primitiveString(inbound.asJsonObject.get(TYPE_KEY))
                ?: return error(ConfigErrorCode.INVALID_INBOUNDS, "Each inbound requires a type")
        }
        if (inboundTypes.any { it !in ALLOWED_INBOUND_TYPES }) {
            return error(ConfigErrorCode.UNSUPPORTED_INBOUND_TYPE, "Only tun, mixed, and http inbounds are allowed")
        }
        if ("tun" !in inboundTypes) return error(ConfigErrorCode.TUN_INBOUND_REQUIRED, "A tun inbound is required")
        return null
    }

    private fun validateOutbounds(root: JsonObject): ConfigValidationError? {
        val outbounds = root.get("outbounds") ?: return error(ConfigErrorCode.MISSING_OUTBOUNDS, "outbounds is required")
        if (!outbounds.isJsonArray || outbounds.asJsonArray.size() == 0) {
            return error(ConfigErrorCode.INVALID_OUTBOUNDS, "outbounds must be a non-empty array")
        }
        outbounds.asJsonArray.forEach { outbound ->
            if (!outbound.isJsonObject) return error(ConfigErrorCode.INVALID_OUTBOUNDS, "Each outbound must be an object")
            val objectValue = outbound.asJsonObject
            val type = primitiveString(objectValue.get(TYPE_KEY))
                ?: return error(ConfigErrorCode.INVALID_OUTBOUNDS, "Each outbound requires a type")
            if (type !in ALLOWED_OUTBOUND_TYPES) {
                return error(ConfigErrorCode.UNSUPPORTED_OUTBOUND_TYPE, "Outbound type '$type' is not allowed")
            }
            val tag = primitiveString(objectValue.get(TAG_KEY))
            if (tag.isNullOrBlank()) return error(ConfigErrorCode.INVALID_TAG, "Each outbound requires a non-blank tag")
            if (type in REMOTE_OUTBOUND_TYPES && !isValidPort(objectValue.get(SERVER_PORT_KEY))) {
                return error(ConfigErrorCode.MISSING_SERVER_PORT, "Outbound '$tag' requires a valid server_port")
            }
            if (type == "socks") {
                if (primitiveString(objectValue.get("server")) != LOOPBACK_SOCKS_SERVER) {
                    return error(ConfigErrorCode.INVALID_SOCKS_OUTBOUND, "SOCKS outbound '$tag' must target loopback")
                }
                if (!isValidPort(objectValue.get(SERVER_PORT_KEY))) {
                    return error(ConfigErrorCode.INVALID_SOCKS_OUTBOUND, "SOCKS outbound '$tag' requires a valid server_port")
                }
                if (objectValue.has("username") || objectValue.has("password")) {
                    return error(ConfigErrorCode.INVALID_SOCKS_OUTBOUND, "SOCKS outbound '$tag' must not carry credentials")
                }
                val version = primitiveString(objectValue.get("version"))
                if (version != null && version != "5") {
                    return error(ConfigErrorCode.INVALID_SOCKS_OUTBOUND, "SOCKS outbound '$tag' must use SOCKS5")
                }
            }
        }
        return null
    }

    private fun isValidPort(value: JsonElement?): Boolean {
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return false
        return try {
            val port = value.asBigDecimal
            port.scale() <= 0 && port >= BigDecimal.ONE && port <= BigDecimal("65535")
        } catch (_: NumberFormatException) {
            false
        }
    }

    private fun primitiveString(value: JsonElement?): String? =
        value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun error(code: ConfigErrorCode, message: String): ConfigValidationError = ConfigValidationError(code, message)

    private fun reject(code: ConfigErrorCode, message: String): ConfigValidationResult.Rejected =
        ConfigValidationResult.Rejected(error(code, message))
}

private class StrictJsonException(
    val code: ConfigErrorCode,
    override val message: String,
) : RuntimeException(message)

private fun parseStrictJson(source: String): JsonElement = JsonReader(StringReader(source)).use { reader ->
    reader.isLenient = false
    val root = readElement(reader)
    if (reader.peek() != JsonToken.END_DOCUMENT) throw StrictJsonException(ConfigErrorCode.MALFORMED_JSON, "Trailing JSON content")
    root
}

private fun decodeUtf8(configBytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(configBytes))
        .toString()
} catch (_: CharacterCodingException) {
    throw StrictJsonException(ConfigErrorCode.MALFORMED_JSON, "Config must be UTF-8")
}

private fun readElement(reader: JsonReader): JsonElement = when (reader.peek()) {
    JsonToken.BEGIN_OBJECT -> readObject(reader)
    JsonToken.BEGIN_ARRAY -> readArray(reader)
    JsonToken.STRING -> JsonPrimitive(reader.nextString())
    JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
    JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
    JsonToken.NULL -> {
        reader.nextNull()
        com.google.gson.JsonNull.INSTANCE
    }
    else -> throw StrictJsonException(ConfigErrorCode.MALFORMED_JSON, "Unexpected JSON token")
}

private fun readObject(reader: JsonReader): JsonObject {
    val names = mutableSetOf<String>()
    val result = JsonObject()
    reader.beginObject()
    while (reader.hasNext()) {
        val name = reader.nextName()
        if (!names.add(name)) throw StrictJsonException(ConfigErrorCode.DUPLICATE_JSON_KEY, "Duplicate key '$name'")
        result.add(name, readElement(reader))
    }
    reader.endObject()
    return result
}

private fun readArray(reader: JsonReader): JsonArray {
    val result = JsonArray()
    reader.beginArray()
    while (reader.hasNext()) result.add(readElement(reader))
    reader.endArray()
    return result
}
