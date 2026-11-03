package com.yokhan.gates.app.auth

import net.openid.appauth.AuthorizationException

internal class MissingStoredAuthSessionException : Exception()

internal object AuthSyncRecoveryPolicy {
    fun shouldOpenLogin(sessionAvailable: Boolean, failure: Throwable): Boolean {
        if (!sessionAvailable || failure is MissingStoredAuthSessionException) return true

        return failure is AuthorizationException &&
            failure.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR &&
            failure.error == AuthorizationException.TokenRequestErrors.INVALID_GRANT.error
    }
}
