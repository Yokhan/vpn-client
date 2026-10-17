package com.yokhan.gates.app.auth

import com.yokhan.gates.app.backup.ProfileMutationGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthSessionTransactionTest {
    private data class Session(var generation: Int)

    @Test
    fun separateProcessGatesLoadTheSuccessorOnlyAfterLocking() = runBlocking {
        val root = Files.createTempDirectory("gates-auth-session").toFile()
        val stateFile = root.resolve("state").apply { writeText("0") }
        val first = ProfileMutationGate(root.resolve("auth.lock"))
        val second = ProfileMutationGate(root.resolve("auth.lock"))
        listOf(first, second).map { gate ->
            async {
                withStoredAuthSession(gate,
                    { Session(stateFile.readText().toInt()) },
                    { stateFile.writeText(it.generation.toString()) }) {
                    delay(20)
                    it.generation++
                }
            }
        }.awaitAll()
        assertEquals("2", stateFile.readText())
    }

    @Test
    fun cancellationDoesNotLoseRotatedRefreshToken() = runBlocking {
        val root = Files.createTempDirectory("gates-auth-cancel").toFile()
        val gate = ProfileMutationGate(root.resolve("auth.lock"))
        val rotated = CompletableDeferred<Unit>()
        var saved = 0
        val job = launch {
            withStoredAuthSession(gate, { Session(0) }, { saved = it.generation }) {
                it.generation = 1
                rotated.complete(Unit)
                delay(30)
            }
        }
        rotated.await()
        job.cancelAndJoin()
        assertEquals(1, saved)
        gate.withLock { assertEquals(1, saved) }
    }
}
