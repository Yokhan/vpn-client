package com.yokhan.gates.windows.service

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.TimeUnit

class SingBoxProcessLauncher(
    private val paths: WindowsServicePaths,
) : TunnelLauncher {

    override fun preflight(config: ValidatedSingBoxConfig) {
        require(Files.isRegularFile(paths.runtimeExecutable)) { "Fixed sing-box runtime is unavailable" }
        Files.createDirectories(paths.dataDirectory)
        val candidate = Files.createTempFile(paths.dataDirectory, "candidate-", ".json")
        try {
            Files.writeString(candidate, config.canonicalJson, StandardCharsets.UTF_8)
            val process = ProcessBuilder(paths.runtimeExecutable.toString(), "check", "-c", candidate.toString())
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(PREFLIGHT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly()
            require(completed && process.exitValue() == 0) { "sing-box rejected the candidate config" }
        } finally {
            Files.deleteIfExists(candidate)
        }
    }

    override fun launch(config: ValidatedSingBoxConfig): TunnelChild {
        require(Files.isRegularFile(paths.runtimeExecutable)) { "Fixed sing-box runtime is unavailable" }
        if (loadPersistedConfig() != config) {
            throw IOException("Persisted tunnel configuration does not match launch request")
        }
        val process = ProcessBuilder(
            paths.runtimeExecutable.toString(),
            "run",
            "-c",
            paths.configFile.toString(),
        )
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(paths.logFile.toFile()))
            .start()
        return ProcessTunnelChild(process)
    }

    override fun loadPersistedConfig(): ValidatedSingBoxConfig? {
        if (!Files.isRegularFile(paths.configFile)) return null
        return when (val validation = SingBoxConfigValidator.validate(Files.readAllBytes(paths.configFile))) {
            is ConfigValidationResult.Accepted -> validation.config
            is ConfigValidationResult.Rejected -> null
        }
    }

    override fun clearPersistedConfig() {
        Files.deleteIfExists(paths.configFile)
    }

    override fun persistConfig(config: ValidatedSingBoxConfig) {
        Files.createDirectories(paths.dataDirectory)
        val temporaryFile = Files.createTempFile(paths.dataDirectory, "active-", ".json")
        Files.writeString(temporaryFile, config.canonicalJson, StandardCharsets.UTF_8)
        try {
            Files.move(temporaryFile, paths.configFile, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporaryFile, paths.configFile, REPLACE_EXISTING)
        }
    }
}

private class ProcessTunnelChild(
    private val process: Process,
) : TunnelChild {
    override fun isAlive(): Boolean = process.isAlive

    override fun requestStop() {
        process.destroy()
    }

    override fun awaitStopped(timeoutMillis: Long): Boolean {
        if (process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) return true
        process.destroyForcibly()
        return process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    }
}

private const val PREFLIGHT_TIMEOUT_SECONDS = 15L
