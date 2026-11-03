package com.yokhan.gates.windows

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

internal const val LEGACY_RUNTIME_HELPER_DISABLED_MESSAGE =
    "The legacy GATES elevated helper is disabled for security. " +
        "Install the per-machine GATES Runtime service or use proxy mode."

internal data class WindowsRuntimeServiceAssets(
    val wrapper: File,
    val jar: File,
    val configuration: File,
    val launcher: File? = null,
)

internal fun resolveWindowsRuntimeServiceAssets(
    launcherPath: String,
    programFilesPath: String,
): WindowsRuntimeServiceAssets? {
    val launcher = File(launcherPath).canonicalFile
    val programFiles = File(programFilesPath).canonicalFile
    if (!launcher.isFile || !launcher.isInside(programFiles)) return null
    val serviceDirectory = File(launcher.parentFile, "app/resources/gates-service").canonicalFile
    if (!serviceDirectory.isInside(launcher.parentFile.canonicalFile)) return null
    return WindowsRuntimeServiceAssets(
        wrapper = File(serviceDirectory, "Gates.Service.exe"),
        jar = File(serviceDirectory, "Gates.Service.jar"),
        configuration = File(serviceDirectory, "Gates.Service.xml"),
        launcher = launcher,
    )
}

class WindowsRuntimeServiceManager(
    private val client: WindowsRuntimeServiceClient = WindowsRuntimeServiceClient(),
    private val launcherPathProvider: () -> String? = ::currentLauncherPath,
    private val programFilesPath: String = System.getenv("ProgramFiles").orEmpty(),
    private val processRunner: (List<String>, File, Int) -> RuntimeCommandResult = ::runWindowsServiceManagerProcess,
) {
    /** Returns null when SCM could not establish whether the service exists. */
    internal fun presence(): Boolean? = runCatching {
        when (queryService().exitCode) {
            0 -> true
            WINDOWS_SERVICE_NOT_INSTALLED_EXIT_CODE -> false
            else -> null
        }
    }.getOrNull()

    fun status(): RuntimeCommandResult {
        val service = queryService()
        val health = client.health()
        val legacyTask = queryLegacyTask()
        return RuntimeCommandResult(
            0,
            buildString {
                appendLine("GATES Runtime service: ${if (service.exitCode == 0) "installed" else "missing"}")
                appendLine("Runtime protocol: ${if (health.exitCode == 0) "ready" else "not reachable"}")
                appendLine("Legacy helper task: ${if (legacyTask.exitCode == 0) "must be removed" else "absent"}")
                append(health.output)
            }.trim(),
        )
    }

    fun install(allowSessionRestore: Boolean = true): RuntimeCommandResult {
        val assets = validatedAssets() ?: return RuntimeCommandResult(
            1,
            "GATES Runtime can only be installed from the per-machine Program Files package.",
        )
        val currentUserSid = queryCurrentUserSid()
            ?: return RuntimeCommandResult(1, "Could not determine the current Windows user SID.")
        val script = buildInstallScript(assets, currentUserSid, allowSessionRestore)
        val installed = runElevatedPowerShell(script, timeoutSeconds = 120)
        if (installed.exitCode != 0) return installed
        if (installed.output == RUNTIME_ACTIVATION_DEFERRED_MESSAGE) return installed
        val health = waitUntilHealthy(30)
        return if (health.exitCode == 0) health else RuntimeCommandResult(
            1,
            "GATES_SETUP_STAGE=service_health; Service setup completed, but the runtime control endpoint did not become reachable.",
        )
    }

    fun uninstall(): RuntimeCommandResult {
        val assets = validatedAssets() ?: return RuntimeCommandResult(1,
            "Verified installed service assets are required to release protection before uninstall.")
        val launcher = assets.launcher?.takeIf { it.isFile } ?: return RuntimeCommandResult(1,
            "The installed GATES launcher is required for offline protection cleanup; nothing was uninstalled.")
        val script = buildUninstallScript(assets, launcher)
        return runElevatedPowerShell(script, timeoutSeconds = 90)
    }

    fun ensureRunning(): RuntimeCommandResult {
        val health = client.health()
        if (health.exitCode == 0) return health
        return install()
    }

    fun prepareWithoutConnecting(): RuntimeCommandResult {
        val health = client.health()
        if (health.exitCode == 0) return health
        return install(allowSessionRestore = false)
    }

    private fun currentAssets(): WindowsRuntimeServiceAssets? {
        val launcher = launcherPathProvider() ?: return null
        if (programFilesPath.isBlank()) return null
        return resolveWindowsRuntimeServiceAssets(launcher, programFilesPath)
    }

    private fun validatedAssets(): WindowsRuntimeServiceAssets? {
        val assets = currentAssets() ?: return null
        if (!assets.wrapper.isFile || !assets.jar.isFile || !assets.configuration.isFile) return null
        if (sha256(assets.wrapper) != PINNED_WINSW_SHA256) return null
        return assets
    }

    private fun waitUntilHealthy(timeoutSeconds: Int): RuntimeCommandResult {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
        var last = client.health()
        while (last.exitCode != 0 && System.nanoTime() < deadline) {
            Thread.sleep(500)
            last = client.health()
        }
        return last
    }

    private fun queryService(): RuntimeCommandResult =
        processRunner(listOf("sc.exe", "query", GATES_SERVICE_NAME), windowsGatesAppDir(), 15)

    private fun queryLegacyTask(): RuntimeCommandResult =
        processRunner(
            listOf("schtasks.exe", "/Query", "/TN", GATES_RUNTIME_HELPER_TASK_NAME, "/FO", "LIST"),
            windowsGatesAppDir(),
            15,
        )

    private fun queryCurrentUserSid(): String? {
        val result = processRunner(listOf("whoami.exe", "/user", "/fo", "csv", "/nh"), windowsGatesAppDir(), 15)
        if (result.exitCode != 0) return null
        return WINDOWS_SID_PATTERN.find(result.output)?.value
    }

    private fun runElevatedPowerShell(script: String, timeoutSeconds: Int): RuntimeCommandResult {
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charset.forName("UTF-16LE")))
        val wrapper = buildElevatedPowerShellWrapper(encoded)
        val result = runCatching {
            processRunner(
                listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", wrapper),
                windowsGatesAppDir(),
                timeoutSeconds,
            )
        }.getOrElse {
            RuntimeCommandResult(ELEVATED_PROCESS_START_FAILED_EXIT_CODE, "")
        }
        return normalizeElevatedSetupResult(result)
    }
}

internal fun buildElevatedPowerShellWrapper(encodedScript: String): String =
    "try { " +
        "\$p = Start-Process -FilePath powershell.exe " +
        "-ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-EncodedCommand','${encodedScript}') " +
        "-Verb RunAs -PassThru -Wait -WindowStyle Hidden; " +
        "if (\$null -eq \$p) { exit 1224 }; exit \$p.ExitCode " +
        "} catch { " +
        "if (\$_.Exception.NativeErrorCode -eq 1223 -or \$_.Exception.HResult -eq -2147023673) { " +
        "exit 1223 }; exit 1224 }"

internal fun buildInstallScript(
    assets: WindowsRuntimeServiceAssets,
    currentUserSid: String,
    allowSessionRestore: Boolean = true,
): String = """
    ${'$'}ErrorActionPreference = 'Stop'
    function Invoke-GatesNative {
        param([string]${'$'}FilePath, [string[]]${'$'}NativeArguments)
        ${ '$' }previousErrorActionPreference = ${ '$' }ErrorActionPreference
        ${'$'}nativePreference = Get-Variable PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue
        ${ '$' }previousNativePreference = if (${ '$' }null -ne ${ '$' }nativePreference) {
            ${ '$' }nativePreference.Value
        } else { ${ '$' }null }
        try {
            ${ '$' }ErrorActionPreference = 'Continue'
            if (${ '$' }null -ne ${ '$' }nativePreference) { ${ '$' }PSNativeCommandUseErrorActionPreference = ${ '$' }false }
            & ${ '$' }FilePath @NativeArguments 2>${'$'}null | Out-Null
            ${ '$' }nativeExitCode = ${ '$' }LASTEXITCODE
        } finally {
            ${ '$' }ErrorActionPreference = ${ '$' }previousErrorActionPreference
            if (${ '$' }null -ne ${ '$' }nativePreference) {
                ${ '$' }PSNativeCommandUseErrorActionPreference = ${ '$' }previousNativePreference
            }
        }
        return ${ '$' }nativeExitCode
    }
    ${'$'}stage = 'prepare_directory'
    try {
    ${'$'}serviceDir = Join-Path ${'$'}env:ProgramData 'GATES\Service'
    New-Item -ItemType Directory -Path ${'$'}serviceDir -Force | Out-Null
    ${ '$' }stage = 'query_service'
    ${ '$' }scExitCode = Invoke-GatesNative 'sc.exe' @('query', ${psSingleQuoted(GATES_SERVICE_NAME)})
    if (${ '$' }scExitCode -ne 0 -and ${ '$' }scExitCode -ne ${WINDOWS_SERVICE_NOT_INSTALLED_EXIT_CODE}) {
        throw 'service_query_failed'
    }
    ${ '$' }serviceInstalled = ${ '$' }scExitCode -eq 0
    ${ '$' }service = if (${ '$' }serviceInstalled) {
        Get-CimInstance Win32_Service -Filter "Name='${GATES_SERVICE_NAME}'" -ErrorAction Stop
    } else { ${ '$' }null }
    if (${ '$' }serviceInstalled -and ${ '$' }null -eq ${ '$' }service) { throw 'service_query_failed' }
    ${ '$' }stage = 'inspect_service'
    ${'$'}wrapperPath = [System.IO.Path]::GetFullPath(${psSingleQuoted(assets.wrapper.absolutePath)})
    if (${ '$' }serviceInstalled -and ${ '$' }service.PathName -notmatch [regex]::Escape(${ '$' }wrapperPath)) {
        ${ '$' }stage = 'inspect_service'
        throw 'conflicting_service'
    }
    ${ '$' }stage = 'prepare_directory'
    ${'$'}tokenPath = Join-Path ${'$'}serviceDir 'control.token'
    ${'$'}existingToken = if (Test-Path -LiteralPath ${'$'}tokenPath -PathType Leaf) {
        [System.IO.File]::ReadAllText(${ '$' }tokenPath, [System.Text.Encoding]::ASCII).Trim()
    } else { '' }
    ${ '$' }tokenIsValid = ${ '$' }existingToken -cmatch '^[0-9a-f]{64}${'$'}'
    if (${ '$' }serviceInstalled -and ${ '$' }service.State -eq 'Running') {
        if (-not ${ '$' }tokenIsValid) {
            ${ '$' }stage = 'preserve_active_service'
            throw 'active_service_token_invalid'
        }
        Write-Output 'GATES_SERVICE_STATE=running_matching'
        Write-Output 'GATES_TOKEN_STATE=preserved'
        exit 0
    }
    ${'$'}acl = New-Object System.Security.AccessControl.DirectorySecurity
    ${'$'}acl.SetAccessRuleProtection(${'$'}true, ${'$'}false)
    ${'$'}inheritance = [System.Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
    ${'$'}propagation = [System.Security.AccessControl.PropagationFlags]::None
    ${'$'}allow = [System.Security.AccessControl.AccessControlType]::Allow
    @('S-1-5-18', 'S-1-5-32-544', ${psSingleQuoted(currentUserSid)}) | ForEach-Object {
        ${'$'}identity = New-Object System.Security.Principal.SecurityIdentifier(${'$'}_)
        ${'$'}rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
            ${'$'}identity, 'FullControl', ${'$'}inheritance, ${'$'}propagation, ${'$'}allow
        )
        ${'$'}acl.AddAccessRule(${'$'}rule)
    }
    Set-Acl -LiteralPath ${'$'}serviceDir -AclObject ${'$'}acl
    if (-not ${ '$' }tokenIsValid) {
        ${ '$' }stage = 'prepare_token'
        ${'$'}bytes = New-Object byte[] 32
        ${'$'}rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
        try { ${'$'}rng.GetBytes(${'$'}bytes) } finally { ${'$'}rng.Dispose() }
        ${'$'}token = ([System.BitConverter]::ToString(${'$'}bytes) -replace '-', '').ToLowerInvariant()
        [System.IO.File]::WriteAllText(${ '$' }tokenPath, ${ '$' }token, [System.Text.Encoding]::ASCII)
        Write-Output 'GATES_TOKEN_STATE=created'
    } else {
        Write-Output 'GATES_TOKEN_STATE=preserved'
    }
    ${'$'}tokenAcl = New-Object System.Security.AccessControl.FileSecurity
    ${'$'}tokenAcl.SetAccessRuleProtection(${'$'}true, ${'$'}false)
    @('S-1-5-18', 'S-1-5-32-544', ${psSingleQuoted(currentUserSid)}) | ForEach-Object {
        ${'$'}identity = New-Object System.Security.Principal.SecurityIdentifier(${'$'}_)
        ${'$'}rule = New-Object System.Security.AccessControl.FileSystemAccessRule(${'$'}identity, 'FullControl', ${'$'}allow)
        ${'$'}tokenAcl.AddAccessRule(${'$'}rule)
    }
    Set-Acl -LiteralPath ${'$'}tokenPath -AclObject ${'$'}tokenAcl
    ${ '$' }stage = 'remove_legacy_task'
    ${ '$' }taskQueryExit = Invoke-GatesNative 'schtasks.exe' @('/Query', '/TN', ${psSingleQuoted(GATES_RUNTIME_HELPER_TASK_NAME)}, '/FO', 'LIST')
    if (${ '$' }taskQueryExit -eq 0) {
        Invoke-GatesNative 'schtasks.exe' @('/End', '/TN', ${psSingleQuoted(GATES_RUNTIME_HELPER_TASK_NAME)}) | Out-Null
        ${ '$' }taskDeleteExit = Invoke-GatesNative 'schtasks.exe' @('/Delete', '/TN', ${psSingleQuoted(GATES_RUNTIME_HELPER_TASK_NAME)}, '/F')
        if (${ '$' }taskDeleteExit -ne 0) { throw 'legacy_task_removal_failed' }
    }
    if (-not ${ '$' }serviceInstalled) {
        ${ '$' }stage = 'install_service'
        ${ '$' }winswExit = Invoke-GatesNative ${psSingleQuoted(assets.wrapper.absolutePath)} @('install')
        if (${ '$' }winswExit -ne 0) { throw 'winsw_install_failed' }
    }
    if (-not ${if (allowSessionRestore) "\$true" else "\$false"} -and
        (Test-Path -LiteralPath (Join-Path ${'$'}serviceDir 'active.json'))) {
        exit 70
    }
    ${ '$' }stage = 'start_service'
    ${ '$' }winswExit = Invoke-GatesNative ${psSingleQuoted(assets.wrapper.absolutePath)} @('start')
    if (${ '$' }winswExit -ne 0) { throw 'winsw_start_failed' }
    Write-Output 'GATES_SERVICE_STATE=start_requested'
    } catch {
        ${ '$' }failureCode = switch (${ '$' }stage) {
            'prepare_directory' { 61; break }
            'query_service' { 62; break }
            'inspect_service' { 63; break }
            'preserve_active_service' { 64; break }
            'prepare_token' { 65; break }
            'remove_legacy_task' { 66; break }
            'install_service' { 67; break }
            'start_service' { 68; break }
            default { 69 }
        }
        exit ${ '$' }failureCode
    }
""".trimIndent()

internal fun buildUninstallScript(assets: WindowsRuntimeServiceAssets, launcherExecutable: File): String = """
    ${'$'}ErrorActionPreference = 'Stop'
    ${'$'}service = Get-CimInstance Win32_Service -Filter "Name='${GATES_SERVICE_NAME}'" -ErrorAction Stop
    if (${ '$' }null -ne ${ '$' }service) {
        ${'$'}expected = '^\s*"?' + [regex]::Escape(${psSingleQuoted(assets.wrapper.absolutePath)}) + '"?(?:\s|${'$'})'
        if (${ '$' }service.PathName -notmatch ${ '$' }expected) { throw 'Conflicting GATES service; uninstall refused' }
        Stop-Service -Name '${GATES_SERVICE_NAME}' -ErrorAction Stop
        (Get-Service -Name '${GATES_SERVICE_NAME}').WaitForStatus('Stopped', [TimeSpan]::FromSeconds(20))
    }
    & ${psSingleQuoted(launcherExecutable.absolutePath)} --runtime-service --cleanup-protection
    if (${ '$' }LASTEXITCODE -ne 0) { throw 'Protection cleanup failed; service was not uninstalled' }
    if (${ '$' }null -ne ${ '$' }service) {
        & ${psSingleQuoted(assets.wrapper.absolutePath)} uninstall
        if (${ '$' }LASTEXITCODE -ne 0) { throw 'GATES service uninstall failed' }
    }
    if (Get-Service -Name '${GATES_SERVICE_NAME}' -ErrorAction SilentlyContinue) { throw 'GATES service still exists' }
""".trimIndent()

private fun currentLauncherPath(): String? =
    ProcessHandle.current().info().command().orElse("").takeIf { it.isNotBlank() }

private fun File.isInside(parent: File): Boolean =
    path.equals(parent.path, ignoreCase = true) ||
        path.startsWith(parent.path + File.separator, ignoreCase = true)

private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
    .digest(file.readBytes())
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun psSingleQuoted(value: String): String = "'${value.replace("'", "''")}'"

private const val GATES_SERVICE_NAME = "GatesVpn"
private const val WINDOWS_SERVICE_NOT_INSTALLED_EXIT_CODE = 1060
private const val UAC_CANCELLED_EXIT_CODE = 1223
private const val ELEVATED_PROCESS_START_FAILED_EXIT_CODE = 1224
private const val WINDOWS_PROCESS_TIMEOUT_EXIT_CODE = 124
private const val MAX_SERVICE_MANAGER_OUTPUT_BYTES = 64 * 1024
private const val OUTPUT_READER_JOIN_MILLIS = 1_000L
private const val PINNED_WINSW_SHA256 =
    "05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da"
private val WINDOWS_SID_PATTERN = Regex("S-1-\\d+(?:-\\d+)+")
internal const val RUNTIME_ACTIVATION_DEFERRED_MESSAGE =
    "GATES service prepared; saved tunnel activation is deferred until an explicit connection."

internal fun normalizeElevatedSetupResult(result: RuntimeCommandResult): RuntimeCommandResult {
    if (result.exitCode == 0) return result
    if (result.exitCode == 70) return RuntimeCommandResult(0, RUNTIME_ACTIVATION_DEFERRED_MESSAGE)
    val (stage, safeError) = when (result.exitCode) {
        61 -> "prepare_directory" to "Could not prepare protected GATES service data."
        62 -> "query_service" to "Windows could not determine whether GatesVpn is installed; no service changes were made."
        63 -> "inspect_service" to "An unrelated service already uses the GatesVpn service name."
        64 -> "preserve_active_service" to "The active service was left untouched because its control token is invalid."
        65 -> "prepare_token" to "Could not prepare protected GATES service data."
        66 -> "remove_legacy_task" to "Could not remove the obsolete GATES scheduled task."
        67 -> "install_service" to "The GATES service wrapper could not install the service."
        68 -> "start_service" to "The installed GATES service could not be started."
        69 -> "setup" to "Windows service preparation failed."
        UAC_CANCELLED_EXIT_CODE -> "uac_approval" to "Administrator approval was canceled."
        ELEVATED_PROCESS_START_FAILED_EXIT_CODE -> "elevated_launch" to "Could not start the elevated Windows setup process."
        WINDOWS_PROCESS_TIMEOUT_EXIT_CODE -> "elevated_timeout" to "The wait limit expired; the elevated operation may still be running. Check GATES service status before retrying."
        else -> "unknown" to "Elevated Windows service setup failed (exit ${result.exitCode})."
    }
    return RuntimeCommandResult(1, "GATES_SETUP_STAGE=$stage; $safeError")
}

internal fun queryWindowsRuntimeServiceInstalled(): Boolean? =
    WindowsRuntimeServiceManager().presence()

private fun runWindowsServiceManagerProcess(
    args: List<String>,
    directory: File,
    timeoutSeconds: Int,
): RuntimeCommandResult {
    directory.mkdirs()
    val process = ProcessBuilder(args).directory(directory).redirectErrorStream(true).start()
    val capturedOutput = ByteArrayOutputStream()
    val outputReader = Thread({
        process.inputStream.use { stream ->
            val buffer = ByteArray(4096)
            var retained = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                val keep = minOf(count, MAX_SERVICE_MANAGER_OUTPUT_BYTES - retained)
                if (keep > 0) {
                    capturedOutput.write(buffer, 0, keep)
                    retained += keep
                }
            }
        }
    }, "gates-service-manager-output").apply {
        isDaemon = true
        start()
    }
    if (!process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)) {
        process.destroyForcibly()
        runCatching { process.inputStream.close() }
        outputReader.join(OUTPUT_READER_JOIN_MILLIS)
        return RuntimeCommandResult(WINDOWS_PROCESS_TIMEOUT_EXIT_CODE, "Process wait expired; elevated child termination is unverified.")
    }
    outputReader.join(OUTPUT_READER_JOIN_MILLIS)
    if (outputReader.isAlive) {
        runCatching { process.inputStream.close() }
        outputReader.join(OUTPUT_READER_JOIN_MILLIS)
    }
    return RuntimeCommandResult(
        process.exitValue(),
        if (outputReader.isAlive) "" else capturedOutput.toString(Charset.defaultCharset()).trim(),
    )
}
