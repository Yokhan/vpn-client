package com.yokhan.gates.app.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProfileMutationGateTest {
    @Test
    fun excludesAWriterInAnotherJvmProcess() = runBlocking {
        val directory = Files.createTempDirectory("gates-process-lock").toFile()
        val file = File(directory, "data.lock")
        val entered = File(directory, "entered")
        val release = File(directory, "release")
        val java = File(System.getProperty("java.home"), "bin/java.exe")
        val executable = if (java.exists()) java else File(java.parentFile, "java")
        val fixture = File("src/test/fixtures/ProfileLockHolder.java").absoluteFile
        assertTrue(fixture.isFile)
        val process = ProcessBuilder(
            executable.absolutePath, fixture.absolutePath,
            file.absolutePath, entered.absolutePath, release.absolutePath,
        ).redirectErrorStream(true).start()
        try {
            withTimeout(10_000L) { while (!entered.exists()) delay(25L) }
            val action = async { ProfileMutationGate(file).withLock { "done" } }
            delay(150L)
            assertFalse(action.isCompleted)
            release.writeText("release")
            assertEquals("done", action.await())
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun independentInstancesWaitAndReadPostImportState() = runBlocking {
        val file = File.createTempFile("gates-mutation", ".lock").apply { deleteOnExit() }
        val importGate = ProfileMutationGate(file)
        val updateGate = ProfileMutationGate(file)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var storedGroup = "old"
        val import = async {
            importGate.withLock {
                entered.complete(Unit)
                release.await()
                storedGroup = "new"
            }
        }
        entered.await()
        val update = async { updateGate.withLock { storedGroup } }
        delay(100)
        assertFalse(update.isCompleted)
        release.complete(Unit)
        import.await()
        assertEquals("new", update.await())
    }

    @Test
    fun waitsForAnExistingFileLockNotOnlyAnInMemoryMutex() = runBlocking {
        val file = File.createTempFile("gates-existing", ".lock").apply { deleteOnExit() }
        RandomAccessFile(file, "rw").use { handle ->
            val lock = handle.channel.lock()
            val action = async { ProfileMutationGate(file).withLock { "done" } }
            delay(100)
            assertFalse(action.isCompleted)
            lock.release()
            assertEquals("done", action.await())
        }
    }

    @Test
    fun releasesLockAfterFailure() = runBlocking {
        val file = File.createTempFile("gates-failure", ".lock").apply { deleteOnExit() }
        val gate = ProfileMutationGate(file)
        assertFailsWith<IllegalStateException> { gate.withLock { error("fixture failure") } }
        assertEquals("recovered", gate.withLock { "recovered" })
    }
}
