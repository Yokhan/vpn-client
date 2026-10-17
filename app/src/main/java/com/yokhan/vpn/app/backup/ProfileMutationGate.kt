package com.yokhan.gates.app.backup

import io.nekohasekai.sagernet.ktx.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

internal class ProfileMutationGate(private val file: File) {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        var localLocked = false
        try {
            withTimeout(30_000L) {
                mutex.lock()
                localLocked = true
            }
            RandomAccessFile(file, "rw").use { handle ->
                val lock = withTimeout(30_000L) {
                    var acquired: FileLock? = null
                    while (acquired == null) {
                        acquired = try {
                            handle.channel.tryLock()
                        } catch (_: OverlappingFileLockException) {
                            null
                        }
                        if (acquired == null) delay(50L)
                    }
                    acquired
                }
                lock.use { block() }
            }
        } finally {
            if (localLocked) mutex.unlock()
        }
    }
}

internal object ProfileMutations {
    private val gate by lazy { ProfileMutationGate(File(app.filesDir, "profile-mutation.lock")) }

    suspend fun <T> withLock(block: suspend () -> T): T = gate.withLock(block)
}
