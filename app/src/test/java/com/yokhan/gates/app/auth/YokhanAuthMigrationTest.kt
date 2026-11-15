package com.yokhan.gates.app.auth

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YokhanAuthMigrationTest {
    @Test
    fun acceptsOnlyTheConfiguredIssuer() {
        assertTrue(authIssuerMatches("https://auth.yokhan.xyz/realms/yokhan", "https://auth.yokhan.xyz/realms/yokhan"))
        assertTrue(authIssuerMatches("https://auth.yokhan.xyz/realms/yokhan/", "https://auth.yokhan.xyz/realms/yokhan"))
        assertFalse(authIssuerMatches("https://auth.yokhanfitness.ru/realms/yokhan", "https://auth.yokhan.xyz/realms/yokhan"))
        assertFalse(authIssuerMatches(null, "https://auth.yokhan.xyz/realms/yokhan"))
    }

    @Test
    fun detectsNetworkReachabilityFailuresInCauseChain() {
        assertTrue(isAuthNetworkTimeoutOrUnreachable(SocketTimeoutException("timeout")))
        assertTrue(isAuthNetworkTimeoutOrUnreachable(RuntimeException("wrapped", UnknownHostException("auth.yokhan.xyz"))))
        assertTrue(isAuthNetworkTimeoutOrUnreachable(ConnectException("refused")))
        assertFalse(isAuthNetworkTimeoutOrUnreachable(IllegalStateException("invalid client")))
        assertFalse(isAuthNetworkTimeoutOrUnreachable(null))
    }
}
