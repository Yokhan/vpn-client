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
private val ALLOWED_OUTBOUND_TYPES = setOf("vless", "hysteria2", "naive", "direct", "block", "urltest", "selector")
private val REMOTE_OUTBOUND_TYPES = setOf("vless", "hysteria2", "naive")
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
}

data class ConfigValidationError(
    val code: ConfigErrorCode,
    val message: String,
)

sealed interface ConfigValidationResult {
    data class Accepted(val config: ValidatedSingBoxConfig) : ConfigValidationResult
    data class Rejected(val error: ConfigValidationError) : ConfigValidationResult
}

object SingBoxConfigValidator {
    private val gson = Gson()

    fun validate(configBytes: ByteArray): ConfigValidationResult {
        if (configBytes.size > MAX_CONFIG_BYTES) return reject(ConfigErrorCode.CONFIG_TOO_LARGE, "Config exceeds 256 KiB")
        val root = try {
            parseStrictJson(decodeUtf8(configBytes))
        } catch (error: StrictJsonException) {
            return reject(error.code, error.message)
        } catch (_: Exception) {
            return reject(ConfigErrorCode.MALFORMED_JSON, "Invalid JSON")
        }
        if (!root.isJsonObject) return reject(ConfigErrorCode.ROOT_NOT_OBJECT, "Config root must be an object")
        return validateRoot(root.asJsonObject)
    }

    private fun validateRoot(root: JsonObject): ConfigValidationResult {
        val topLevelError = validateTopLevelSections(root) ?: validateTree(root, mutableSetOf()) ?: validateInbounds(root) ?: validateOutbounds(root)
        if (topLevelError != null) return ConfigValidationResult.Rejected(topLevelError)
        return ConfigValidationResult.Accepted(ValidatedSingBoxConfig(gson.toJson(root)))
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
