package com.yokhan.gates.app.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class YokhanKeycloakAuthUpgradeTest {

    @Test
    fun reloadsActiveTunnelStrictlyBeforeRevokingBootstrapAccess() = runBlocking {
        val events = mutableListOf<String>()

        YokhanKeycloakAuth.finalizeAuthenticatedProfileImport(
            isTunnelActive = { true },
            reloadTunnel = { events += "reload" },
            waitForTunnelReconnect = { events += "wait" },
            revokeBootstrap = { events += "revoke" },
        )

        assertEquals(listOf("reload", "wait", "revoke"), events)
    }

    @Test
    fun skipsReloadWhenTunnelIsStoppedAndStillRevokesBootstrap() = runBlocking {
        val events = mutableListOf<String>()

        YokhanKeycloakAuth.finalizeAuthenticatedProfileImport(
            isTunnelActive = { false },
            reloadTunnel = { events += "reload" },
            waitForTunnelReconnect = { events += "wait" },
            revokeBootstrap = { events += "revoke" },
        )

        assertEquals(listOf("revoke"), events)
    }

    @Test
    fun ignoresRevokeFailureAfterReloadingActiveTunnel() = runBlocking {
        val events = mutableListOf<String>()

        YokhanKeycloakAuth.finalizeAuthenticatedProfileImport(
            isTunnelActive = { true },
            reloadTunnel = { events += "reload" },
            waitForTunnelReconnect = { events += "wait" },
            revokeBootstrap = {
                events += "revoke_attempt"
                throw IllegalStateException("offline")
            },
        )

        assertEquals(listOf("reload", "wait", "revoke_attempt"), events)
    }
}
