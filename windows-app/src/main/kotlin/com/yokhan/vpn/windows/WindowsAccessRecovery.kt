package com.yokhan.gates.windows

import com.google.gson.JsonParser

/** Coarse, sanitized stage where authenticated access setup stopped. */
enum class WindowsAccessStage {
    AUTHORIZATION,
    TOKEN,
    PROFILE,
    ACCESS_REQUEST,
}

/** Bounded, secret-free codes derived from fixed API codes or HTTP status. */
enum class WindowsAccessCode {
    AUTH_START_FAILED,
    AUTH_DENIED,
    AUTH_EXPIRED,
    AUTH_TIMEOUT,
    AUTH_REQUIRED,
    AUTH_FORBIDDEN,
    AUTH_UNAVAILABLE,
    ACCESS_NOT_LINKED,
    ACCESS_PENDING,
    USER_NOT_FOUND,
    DEVICE_NOT_FOUND,
    ROOM_FORBIDDEN,
    APP_ROUTE_UNAVAILABLE,
    RATE_LIMITED,
    NOT_FOUND,
    CONFLICT,
    SERVER_ERROR,
    TRANSPORT_ERROR,
    PROFILE_INVALID,
    PROFILE_STORE_FAILED,
    REQUEST_OPEN,
    REQUEST_FAILED,
    UNKNOWN,
}

data class WindowsAccessFailure(
    val stage: WindowsAccessStage,
    val code: WindowsAccessCode,
    val httpStatus: Int? = null,
)

internal const val WINDOWS_ACCESS_RESTART_COMMAND = "login"
internal const val WINDOWS_ACCESS_RETRY_COMMAND = "import-app"
internal const val WINDOWS_ACCESS_REQUEST_COMMAND = "access-request"

/** The primary access action refreshes a saved profile, otherwise it starts sign-in. */
internal fun windowsAccessPrimaryCommand(signedIn: Boolean): String =
    if (signedIn) WINDOWS_ACCESS_RETRY_COMMAND else WINDOWS_ACCESS_RESTART_COMMAND

/** A forced restart always starts browser sign-in, even with a saved account. */
internal fun windowsAccessCommand(signedIn: Boolean, forceLogin: Boolean): String =
    if (forceLogin) WINDOWS_ACCESS_RESTART_COMMAND else windowsAccessPrimaryCommand(signedIn)

private val WINDOWS_BACKEND_ACCESS_CODES = mapOf(
    "GATES_ACCESS_NOT_LINKED" to WindowsAccessCode.ACCESS_NOT_LINKED,
    "GATES_AUTH_REQUIRED" to WindowsAccessCode.AUTH_REQUIRED,
    "GATES_AUTH_UNAVAILABLE" to WindowsAccessCode.AUTH_UNAVAILABLE,
    "GATES_AUTH_FORBIDDEN" to WindowsAccessCode.AUTH_FORBIDDEN,
    "GATES_USER_NOT_FOUND" to WindowsAccessCode.USER_NOT_FOUND,
    "GATES_DEVICE_NOT_FOUND" to WindowsAccessCode.DEVICE_NOT_FOUND,
    "GATES_ROOM_FORBIDDEN" to WindowsAccessCode.ROOM_FORBIDDEN,
    "GATES_APP_ROUTE_UNAVAILABLE" to WindowsAccessCode.APP_ROUTE_UNAVAILABLE,
)

/** Read only the fixed `code` field; raw messages and bodies are never returned. */
internal fun windowsBackendAccessCode(body: String): String? = runCatching {
    JsonParser.parseString(body).asJsonObject.get("code")?.asString
}.getOrNull()?.takeIf { it in WINDOWS_BACKEND_ACCESS_CODES }

internal fun windowsAccessFailure(
    stage: WindowsAccessStage,
    httpStatus: Int,
    backendCode: String? = null,
): WindowsAccessFailure = WindowsAccessFailure(
    stage = stage,
    code = WINDOWS_BACKEND_ACCESS_CODES[backendCode] ?: when {
        httpStatus == 401 -> WindowsAccessCode.AUTH_REQUIRED
        httpStatus == 403 -> WindowsAccessCode.ACCESS_NOT_LINKED
        httpStatus == 404 -> WindowsAccessCode.NOT_FOUND
        httpStatus == 409 -> WindowsAccessCode.CONFLICT
        httpStatus == 429 -> WindowsAccessCode.RATE_LIMITED
        httpStatus >= 500 -> WindowsAccessCode.SERVER_ERROR
        else -> WindowsAccessCode.UNKNOWN
    },
    httpStatus = httpStatus,
)

internal fun windowsTokenAccessFailure(httpStatus: Int, body: String): WindowsAccessFailure {
    val error = runCatching {
        JsonParser.parseString(body).asJsonObject.get("error")?.asString
    }.getOrNull()
    val code = when (error) {
        "invalid_grant", "invalid_token", "expired_token" -> WindowsAccessCode.AUTH_EXPIRED
        "access_denied" -> WindowsAccessCode.AUTH_DENIED
        else -> when {
            httpStatus == 401 -> WindowsAccessCode.AUTH_REQUIRED
            httpStatus == 429 -> WindowsAccessCode.RATE_LIMITED
            httpStatus >= 500 -> WindowsAccessCode.SERVER_ERROR
            else -> WindowsAccessCode.UNKNOWN
        }
    }
    return WindowsAccessFailure(WindowsAccessStage.TOKEN, code, httpStatus)
}

internal fun windowsProfileAccessFailure(output: String): WindowsAccessFailure =
    WindowsAccessFailure(
        stage = WindowsAccessStage.PROFILE,
        code = if ("Не удалось сохранить" in output || "восстановить настройки" in output) {
            WindowsAccessCode.PROFILE_STORE_FAILED
        } else {
            WindowsAccessCode.PROFILE_INVALID
        },
    )

/** Classify an unexpected token-refresh failure without exposing its raw message. */
internal fun windowsSessionRefreshFailure(error: Exception): WindowsAccessCode {
    val message = error.message.orEmpty()
    return when {
        "HTTP 429" in message -> WindowsAccessCode.RATE_LIMITED
        "HTTP 5" in message -> WindowsAccessCode.SERVER_ERROR
        "HTTP 4" in message -> WindowsAccessCode.AUTH_REQUIRED
        else -> WindowsAccessCode.TRANSPORT_ERROR
    }
}
