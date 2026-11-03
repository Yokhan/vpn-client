package com.yokhan.gates.app.auth

import net.openid.appauth.AuthorizationException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthSyncRecoveryPolicyTest {
    @Test
    fun transientAndUnclassifiedFailuresKeepTheSavedSession() {
        val failures = listOf(
            java.net.SocketTimeoutException("timeout"),
            java.io.IOException("network unavailable"),
            IllegalStateException("HTTP 503"),
            IllegalArgumentException("invalid configuration payload"),
            AuthorizationException(
                AuthorizationException.TYPE_GENERAL_ERROR,
                0,
                "configuration unavailable",
                null,
                null,
                null,
            ),
        )

        failures.forEach { failure ->
            assertFalse(AuthSyncRecoveryPolicy.shouldOpenLogin(sessionAvailable = true, failure))
        }
    }

    @Test
    fun onlyTokenEndpointInvalidGrantConfirmsRefreshRejection() {
        val invalidGrant = AuthorizationException.TokenRequestErrors.INVALID_GRANT
        val failure = AuthorizationException(
            AuthorizationException.TYPE_OAUTH_TOKEN_ERROR,
            invalidGrant.code,
            invalidGrant.error,
            invalidGrant.errorDescription,
            invalidGrant.errorUri,
            null,
        )
        assertTrue(AuthSyncRecoveryPolicy.shouldOpenLogin(sessionAvailable = true, failure))

        val apiUnauthorized = AuthorizationException(
            AuthorizationException.TYPE_RESOURCE_SERVER_AUTHORIZATION_ERROR,
            invalidGrant.code,
            invalidGrant.error,
            null,
            null,
            null,
        )
        assertFalse(AuthSyncRecoveryPolicy.shouldOpenLogin(sessionAvailable = true, apiUnauthorized))
    }

    @Test
    fun absentOrInvalidStoredSessionAllowsLogin() {
        assertTrue(AuthSyncRecoveryPolicy.shouldOpenLogin(sessionAvailable = false, IllegalStateException()))
        assertTrue(
            AuthSyncRecoveryPolicy.shouldOpenLogin(
                sessionAvailable = true,
                MissingStoredAuthSessionException(),
            ),
        )
    }
}
