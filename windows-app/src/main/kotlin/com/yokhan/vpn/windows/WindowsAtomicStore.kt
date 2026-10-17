package com.yokhan.gates.windows

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class WindowsAtomicStore(root: File) {
    private val rootPath = root.canonicalFile.toPath().normalize()
    private val lockPath = rootPath.resolve(LOCK_FILE_NAME)
    private val rootKey = rootPath.toString().let { path ->
        if (File.separatorChar == '\\') path.lowercase(Locale.ROOT) else path
    }
    private val localLock = rootLocks.computeIfAbsent(rootKey) {
        ReentrantLock()
    }

    fun <T> transaction(action: () -> T): T = localLock.withLock {
        if (localLock.holdCount > 1) return@withLock action()

        Files.createDirectories(rootPath)
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { action() }
        }
    }

    fun readBytes(target: File): ByteArray = transaction {
        Files.readAllBytes(resolveTarget(target))
    }

    fun readText(target: File, charset: Charset = Charsets.UTF_8): String =
        readBytes(target).toString(charset)

    fun writeAtomic(target: File, bytes: ByteArray) {
        val destination = resolveTarget(target)

        transaction {
            val parent = requireNotNull(destination.parent) { "Atomic store target must have a parent directory." }
            Files.createDirectories(parent)
            require(target.canonicalFile.toPath().normalize().startsWith(rootPath)) {
                "Atomic store target must remain inside its root."
            }

            val staging = Files.createTempFile(parent, ".gates-atomic-", ".tmp")
            try {
                FileChannel.open(staging, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                Files.move(
                    staging,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } finally {
                Files.deleteIfExists(staging)
            }
        }
    }

    private fun resolveTarget(target: File): java.nio.file.Path {
        val destination = target.canonicalFile.toPath().normalize()
        require(destination != rootPath && destination.startsWith(rootPath)) {
            "Atomic store target must be inside its root."
        }
        require(destination != lockPath) { "Atomic store target cannot replace its lock file." }
        return destination
    }

    private companion object {
        const val LOCK_FILE_NAME = ".windows-atomic-store.lock"
        val rootLocks = ConcurrentHashMap<String, ReentrantLock>()
    }
}
