package com.yokhan.gates.windows

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class WindowsSingleInstanceGuard private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val serverSocket: ServerSocket,
    private val portFile: File,
    private val token: String,
    @Volatile var onActivateRequest: () -> Unit = {},
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val listenerThread = Thread({
        while (!closed.get() && !serverSocket.isClosed) {
            val client = runCatching { serverSocket.accept() }.getOrNull() ?: break
            runCatching {
                client.use { socket ->
                    socket.soTimeout = 500
                    val line = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine().orEmpty().trim()
                    if (line == token) {
                        onActivateRequest()
                    }
                }
            }
        }
    }, "gates-ui-single-instance").apply {
        isDaemon = true
        start()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { serverSocket.close() }
        runCatching { portFile.delete() }
        runCatching { lock.release() }
        runCatching { channel.close() }
        runCatching { listenerThread.interrupt() }
    }

    companion object {
        fun tryAcquireOrWakeExisting(
            appDir: File = windowsGatesAppDir(),
            wakeExisting: Boolean = true,
            onActivateRequest: () -> Unit = {},
        ): WindowsSingleInstanceGuard? {
            appDir.mkdirs()
            val lockFile = File(appDir, "ui-instance.lock")
            val portFile = File(appDir, "ui-instance.port")
            val channel = runCatching {
                FileChannel.open(
                    lockFile.toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                )
            }.getOrNull() ?: return null
            val lock = runCatching { channel.tryLock() }.getOrNull()
            if (lock == null) {
                runCatching { channel.close() }
                if (wakeExisting) {
                    wakeExistingInstance(portFile)
                }
                return null
            }
            val token = UUID.randomUUID().toString()
            val server = runCatching {
                ServerSocket(0, 4, InetAddress.getLoopbackAddress())
            }.getOrElse {
                runCatching { lock.release() }
                runCatching { channel.close() }
                return null
            }
            runCatching {
                portFile.writeText("${server.localPort}:$token", StandardCharsets.UTF_8)
            }
            return WindowsSingleInstanceGuard(
                channel = channel,
                lock = lock,
                serverSocket = server,
                portFile = portFile,
                token = token,
                onActivateRequest = onActivateRequest,
            )
        }

        private fun wakeExistingInstance(portFile: File): Boolean {
            if (!portFile.isFile) return false
            val payload = runCatching { portFile.readText(StandardCharsets.UTF_8).trim() }.getOrNull() ?: return false
            val colon = payload.indexOf(':')
            if (colon <= 0) return false
            val port = payload.substring(0, colon).toIntOrNull() ?: return false
            val token = payload.substring(colon + 1).trim()
            if (port !in 1..65535 || token.isBlank()) return false
            return runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500)
                    socket.getOutputStream().write("$token\n".toByteArray(StandardCharsets.UTF_8))
                    socket.getOutputStream().flush()
                }
                true
            }.getOrDefault(false)
        }
    }
}
