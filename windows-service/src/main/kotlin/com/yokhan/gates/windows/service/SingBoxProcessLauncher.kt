package com.yokhan.gates.windows.service

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.TimeUnit

class SingBoxProcessLauncher(
    private val paths: WindowsServicePaths,
    private val protection: SelectedAppProtectionController = DisabledSelectedAppProtection,
    private val protectionAlias: String? = null,
    private val serviceProcessExecutablePath: String? = null,
    private val sidecar: XraySidecar = DisabledXraySidecar,
): TunnelLauncher {

    override fun coreExecutablePath(): String = paths.runtimeExecutable.toString()

    override fun reservedProtectionPaths(): Set<String>? = serviceProcessExecutablePath?.let {
        setOf(paths.runtimeExecutable.toString(), paths.launcherExecutable.toString(), paths.xrayExecutable.toString(), it)
    }

    override fun preflight(config: ValidatedSingBoxConfig) {
        require(Files.isRegularFile(paths.runtimeExecutable)) { "Fixed sing-box runtime is unavailable" }
        val sidecarFailure = sidecar.preflight(config.sidecar)
        require(sidecarFailure == null) { "XHTTP sidecar preflight failed: $sidecarFailure" }
        Files.createDirectories(paths.dataDirectory)
        val candidate = Files.createTempFile(paths.dataDirectory, "candidate-", ".json")
        var deleteCandidate = true
        try {
            Files.writeString(candidate, runtimeConfigText(config), StandardCharsets.UTF_8)
            val process = preflightProcessBuilder(paths.runtimeExecutable, candidate).start()
            var cleanupAttempted = false
            try {
                val completed = process.waitFor(PREFLIGHT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!completed) {
                    cleanupAttempted = true
                    deleteCandidate = false
                    deleteCandidate = terminatePreflightProcess(process, PREFLIGHT_CLEANUP_TIMEOUT_MILLIS)
                    if (!deleteCandidate) {
                        throw IOException("sing-box preflight process did not stop after timeout")
                    }
                    throw IllegalArgumentException("sing-box rejected the candidate config")
                }
                require(process.exitValue() == 0) { "sing-box rejected the candidate config" }
            } catch (interrupted: InterruptedException) {
                if (!cleanupAttempted) deleteCandidate = cleanupPreflightAfterFailure(process, interrupted)
                Thread.currentThread().interrupt()
                throw interrupted
            } catch (failure: Throwable) {
                if (!cleanupAttempted) deleteCandidate = cleanupPreflightAfterFailure(process, failure)
                throw failure
            }
        } finally {
            if (deleteCandidate) Files.deleteIfExists(candidate)
        }
    }

    override fun launch(config: ValidatedSingBoxConfig): TunnelChild {
        require(Files.isRegularFile(paths.runtimeExecutable)) { "Fixed sing-box runtime is unavailable" }
        if (loadPersistedConfig() != config) {
            throw IOException("Persisted tunnel configuration does not match launch request")
        }
        val runtimeConfig = paths.dataDirectory.resolve(RUNTIME_CONFIG_FILE)
        Files.writeString(runtimeConfig, runtimeConfigText(config), StandardCharsets.UTF_8)
        val sidecarSpec = config.sidecar
        if (sidecarSpec != null) {
            when (val result = sidecar.start(sidecarSpec)) {
                is SidecarStartResult.Failed -> if (sidecarSpec.required) {
                    throw IOException("XHTTP sidecar is unavailable: ${result.reason}")
                }
                else -> Unit
            }
        }
        val process = try {
            ProcessBuilder(
                paths.runtimeExecutable.toString(),
                "run",
                "-c",
                runtimeConfig.toString(),
            )
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(paths.logFile.toFile()))
                .start()
        } catch (failure: Exception) {
            sidecar.stop()
            throw failure
        }
        return XhttpTunnelChild(
            tunnel = ProcessTunnelChild(process),
            sidecar = if (sidecarSpec != null) sidecar else null,
            spec = sidecarSpec,
        )
    }

    override fun loadPersistedConfig(): ValidatedSingBoxConfig? {
        if (!Files.isRegularFile(paths.configFile)) return null
        return when (val validation = SingBoxConfigValidator.validate(
            Files.readAllBytes(paths.configFile), coreExecutablePath(), reservedProtectionPaths(),
        )) {
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
        Files.writeString(temporaryFile, persistedConfigJson(config), StandardCharsets.UTF_8)
        try {
            Files.move(temporaryFile, paths.configFile, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporaryFile, paths.configFile, REPLACE_EXISTING)
        }
    }

    override fun loadStandingProtection(): SelectedAppProtection? {
        if (!Files.isRegularFile(paths.standingProtectionFile)) return null
        return when (val result = SingBoxConfigValidator.validateProtectionPayload(
            Files.readAllBytes(paths.standingProtectionFile),
            coreExecutablePath(),
            reservedProtectionPaths(),
        )) {
            is ProtectionPayloadResult.Accepted -> result.protection
            is ProtectionPayloadResult.Rejected -> null
        }
    }

    override fun persistStandingProtection(protection: SelectedAppProtection) {
        Files.createDirectories(paths.dataDirectory)
        val temporaryFile = Files.createTempFile(paths.dataDirectory, "standing-protection-", ".json")
        val payload = com.google.gson.JsonObject().apply {
            addProperty("schema", 1)
            add("protected_executables", com.google.gson.JsonArray().also { array ->
                protection.protectedExecutables.forEach(array::add)
            })
        }
        Files.writeString(temporaryFile, com.google.gson.Gson().toJson(payload), StandardCharsets.UTF_8)
        try {
            Files.move(temporaryFile, paths.standingProtectionFile, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporaryFile, paths.standingProtectionFile, REPLACE_EXISTING)
        }
    }

    override fun clearStandingProtection() {
        Files.deleteIfExists(paths.standingProtectionFile)
    }

    override fun armProtection(config: ValidatedSingBoxConfig) = protection.arm(config)
    override fun permitProtection(
        config: ValidatedSingBoxConfig,
        confirmChildAlive: () -> Unit,
    ): ProtectionPermitResult = protection.permit(config, confirmChildAlive)
    override fun blockProtection() = protection.block()
    override fun releaseProtection() = protection.release()
    override fun closeProtectionPermitsOnly() = protection.closePermitsOnly()
    override fun protectionStatus(): ProtectionStatus = protection.status()

    private fun protectionAliasFor(config: ValidatedSingBoxConfig): String? =
        if (config.protection == null) null else protectionAlias

    /**
     * The XHTTP helper is a transport-owned process, so its exact installed
     * path is derived from the service paths rather than from user routing
     * preferences. The rule is injected only for configs that own a sidecar.
     */
    private fun runtimeConfigText(config: ValidatedSingBoxConfig): String =
        runtimeConfigJson(
            config,
            protectionAliasFor(config),
            transportDirectProcessPaths = if (config.sidecar == null) {
                emptyList()
            } else {
                listOf(paths.xrayExecutable.toAbsolutePath().normalize().toString())
            },
        )

    private fun persistedConfigJson(config: ValidatedSingBoxConfig): String {
        if (config.protection == null && config.sidecar == null) return config.canonicalJson
        val root = com.google.gson.JsonParser.parseString(config.canonicalJson).asJsonObject
        if (config.protection != null) {
            root.add("gates_protection", com.google.gson.JsonObject().apply {
                addProperty("schema", 1)
                add("protected_executables", com.google.gson.JsonArray().also { array ->
                    config.protection.protectedExecutables.forEach(array::add)
                })
            })
        }
        config.sidecar?.let { sidecar -> root.add("gates_xhttp", sidecar.toJsonObject()) }
        return com.google.gson.Gson().toJson(root)
    }
}

internal class ProcessTunnelChild(
    private val process: Process,
    private val nanoTime: () -> Long = System::nanoTime,
) : TunnelChild {
    override fun isAlive(): Boolean = process.isAlive

    override fun requestStop() {
        process.destroy()
    }

    override fun awaitStopped(timeoutMillis: Long): Boolean {
        if (!process.isAlive) return true

        val budgetNanos = if (timeoutMillis <= 0L) 0L else TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val startedAt = nanoTime()
        try {
            val gracefulRemaining = remainingBudgetNanos(startedAt, budgetNanos, nanoTime())
            if (gracefulRemaining > 1L && process.waitFor(gracefulRemaining / 2L, TimeUnit.NANOSECONDS)) {
                return true
            }
        } catch (interrupted: InterruptedException) {
            throw forceAfterInterruption(interrupted)
        }

        if (!process.isAlive) return true
        process.destroyForcibly()
        val forceRemaining = remainingBudgetNanos(startedAt, budgetNanos, nanoTime())
        if (forceRemaining <= 0L) return !process.isAlive

        return try {
            process.waitFor(forceRemaining, TimeUnit.NANOSECONDS) || !process.isAlive
        } catch (interrupted: InterruptedException) {
            throw forceAfterInterruption(interrupted)
        }
    }

    private fun forceAfterInterruption(interrupted: InterruptedException): InterruptedException {
        try {
            process.destroyForcibly()
        } catch (forceFailure: Throwable) {
            interrupted.addSuppressed(forceFailure)
        } finally {
            Thread.currentThread().interrupt()
        }
        return interrupted
    }
}

internal fun preflightProcessBuilder(runtimeExecutable: Path, candidate: Path): ProcessBuilder =
    ProcessBuilder(runtimeExecutable.toString(), "check", "-c", candidate.toString())
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)

internal fun terminatePreflightProcess(process: Process, timeoutMillis: Long): Boolean {
    if (!process.isAlive) return true
    process.destroyForcibly()
    val budgetNanos = if (timeoutMillis <= 0L) 0L else TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    val startedAt = System.nanoTime()
    var interrupted = false
    try {
        while (process.isAlive) {
            val remaining = remainingBudgetNanos(startedAt, budgetNanos, System.nanoTime())
            if (remaining <= 0L) return false
            try {
                if (process.waitFor(remaining, TimeUnit.NANOSECONDS)) return true
            } catch (_: InterruptedException) {
                interrupted = true
                process.destroyForcibly()
            }
        }
        return true
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}

private fun cleanupPreflightAfterFailure(process: Process, original: Throwable): Boolean {
    if (!process.isAlive) return true
    try {
        if (!terminatePreflightProcess(process, PREFLIGHT_CLEANUP_TIMEOUT_MILLIS)) {
            original.addSuppressed(IOException("sing-box preflight process termination was not confirmed"))
        }
    } catch (cleanupFailure: Throwable) {
        original.addSuppressed(cleanupFailure)
    }
    return !process.isAlive
}

internal fun remainingBudgetNanos(startedAt: Long, budgetNanos: Long, now: Long): Long {
    if (budgetNanos <= 0L) return 0L
    val elapsed = now - startedAt
    return if (elapsed < 0L || elapsed >= budgetNanos) 0L else budgetNanos - elapsed
}

private const val PREFLIGHT_TIMEOUT_SECONDS = 15L
private const val PREFLIGHT_CLEANUP_TIMEOUT_MILLIS = 1_000L
private const val RUNTIME_CONFIG_FILE = "runtime-config.json"
