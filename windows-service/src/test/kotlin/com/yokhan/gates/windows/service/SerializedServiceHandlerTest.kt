package com.yokhan.gates.windows.service

import java.util.Base64
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class SerializedServiceHandlerTest {
    private val token = "a".repeat(64)

    @Test
    fun rejectsDangerousConfigBeforeLaunchingTheRuntime() {
        var launches = 0
        val supervisor = SerializedTunnelSupervisor {
            launches += 1
            FakeTunnelChild()
        }
        val handler = SerializedServiceHandler(supervisor, token)
        val config = """{"inbounds":[{"type":"tun"}],"outbounds":[{"type":"direct","tag":"default"}],"route":{"file":"bad"}}"""

        assertEquals("2|ERROR|FORBIDDEN_KEY", handler.handle(startFrame(config), clientNonce, serverNonce))
        assertEquals(0, launches)
    }

    @Test
    fun startsOnlyOneOwnedChildForValidConfig() {
        var launches = 0
        val supervisor = SerializedTunnelSupervisor {
            launches += 1
            FakeTunnelChild()
        }
        val handler = SerializedServiceHandler(supervisor, token)
        val config = """{"inbounds":[{"type":"tun"}],"outbounds":[{"type":"direct","tag":"default"}]}"""

        assertEquals("2|OK|STARTED", handler.handle(startFrame(config), clientNonce, serverNonce))
        assertEquals("2|OK|ALREADY_RUNNING", handler.handle(startFrame(config), clientNonce, serverNonce))
        assertEquals(1, launches)
    }

    @Test
    fun rejectsAWellFormedTokenThatDoesNotMatchTheInstalledApp() {
        var launches = 0
        val handler = SerializedServiceHandler(SerializedTunnelSupervisor {
            launches += 1
            FakeTunnelChild()
        }, token)
        val config = """{"inbounds":[{"type":"tun"}],"outbounds":[{"type":"direct","tag":"default"}]}"""

        val foreignProof = ControlAuthentication.clientProof("b".repeat(64), clientNonce, serverNonce)
        assertEquals(
            "2|ERROR|UNAUTHORIZED",
            handler.handle(startFrame(config, foreignProof), clientNonce, serverNonce),
        )
        assertEquals(0, launches)
    }

    @Test
    fun digestRequiresAuthenticationAndDoesNotReturnConfigSecrets() {
        val supervisor = SerializedTunnelSupervisor { FakeTunnelChild() }
        supervisor.start(ValidatedSingBoxConfig("{\"password\":\"never-return-this\"}"))
        val handler = SerializedServiceHandler(supervisor, token)
        val proof = ControlAuthentication.clientProof(token, clientNonce, serverNonce)
        assertEquals("2|OK|${supervisor.configurationDigest()}", handler.handle("2|$proof|CONFIG_DIGEST|", clientNonce, serverNonce))
        val foreign = ControlAuthentication.clientProof("b".repeat(64), clientNonce, serverNonce)
        assertEquals("2|ERROR|UNAUTHORIZED", handler.handle("2|$foreign|CONFIG_DIGEST|", clientNonce, serverNonce))
    }

    @Test
    fun protectionStatusUsesTheExistingControlAuthentication() {
        val supervisor = object : TunnelLauncher {
            override fun launch(config: ValidatedSingBoxConfig): TunnelChild = FakeTunnelChild()
            override fun protectionStatus(): ProtectionStatus = ProtectionStatus.BLOCKED
        }
        val handler = SerializedServiceHandler(SerializedTunnelSupervisor(supervisor), token)
        val proof = ControlAuthentication.clientProof(token, clientNonce, serverNonce)
        val foreign = ControlAuthentication.clientProof("b".repeat(64), clientNonce, serverNonce)

        assertEquals("2|OK|BLOCKED", handler.handle("2|$proof|PROTECTION_STATUS|", clientNonce, serverNonce))
        assertEquals("2|ERROR|UNAUTHORIZED", handler.handle("2|$foreign|PROTECTION_STATUS|", clientNonce, serverNonce))
    }

    @Test
    fun persistenceFailureDuringStopReturnsSanitizedError() {
        val launcher = object : TunnelLauncher {
            override fun launch(config: ValidatedSingBoxConfig): TunnelChild = FakeTunnelChild()
            override fun clearPersistedConfig() { throw IOException("password=never-return-this") }
        }
        val handler = SerializedServiceHandler(SerializedTunnelSupervisor(launcher), token)
        val proof = ControlAuthentication.clientProof(token, clientNonce, serverNonce)
        assertEquals("2|ERROR|STOP_FAILED", handler.handle("2|$proof|STOP|", clientNonce, serverNonce))
    }

    private fun startFrame(
        config: String,
        authProof: String = ControlAuthentication.clientProof(token, clientNonce, serverNonce),
    ): String {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(config.toByteArray())
        return "2|$authProof|START_TUNNEL|$payload"
    }

    private class FakeTunnelChild : TunnelChild {
        override fun isAlive(): Boolean = true

        override fun requestStop() = Unit

        override fun awaitStopped(timeoutMillis: Long): Boolean = !isAlive()
    }

    private val clientNonce = "c".repeat(64)
    private val serverNonce = "d".repeat(64)
}
