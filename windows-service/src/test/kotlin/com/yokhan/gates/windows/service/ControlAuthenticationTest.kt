package com.yokhan.gates.windows.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ControlAuthenticationTest {
    @Test
    fun authenticatesBothSidesWithoutSendingTheInstalledToken() {
        val token = "a".repeat(64)
        val clientNonce = "b".repeat(64)
        val serverNonce = "c".repeat(64)
        val serverProof = ControlAuthentication.serverProof(token, clientNonce, serverNonce)
        val clientProof = ControlAuthentication.clientProof(token, clientNonce, serverNonce)

        assertFalse(serverProof.contains(token))
        assertFalse(clientProof.contains(token))
        assertTrue(ControlAuthentication.matches(serverProof, serverProof))
        assertFalse(ControlAuthentication.matches(serverProof, clientProof))
    }

    @Test
    fun parsesOnlyVersionedNonceBoundHandshakeFrames() {
        val clientNonce = "b".repeat(64)
        val serverNonce = "c".repeat(64)
        val proof = "d".repeat(64)

        assertEquals(clientNonce, ControlAuthentication.parseHello(ControlAuthentication.hello(clientNonce)))
        assertNotNull(ControlAuthentication.parseChallenge(ControlAuthentication.challenge(serverNonce, proof)))
        assertEquals(null, ControlAuthentication.parseHello("2|HELLO|short"))
    }
}
