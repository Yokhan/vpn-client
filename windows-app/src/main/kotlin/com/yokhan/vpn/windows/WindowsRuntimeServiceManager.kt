package com.yokhan.gates.windows

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
    )
}

class WindowsRuntimeServiceManager(
    private val client: WindowsRuntimeServiceClient = WindowsRuntimeServiceClient(),
    private val launcherPathProvider: () -> String? = ::currentLauncherPath,
    private val programFilesPath: String = System.getenv("ProgramFiles").orEmpty(),
) {
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

    fun install(): RuntimeCommandResult {
        val assets = validatedAssets() ?: return RuntimeCommandResult(
            1,
            "GATES Runtime can only be installed from the per-machine Program Files package.",
        )
        val currentUserSid = queryCurrentUserSid()
            ?: return RuntimeCommandResult(1, "Could not determine the current Windows user SID.")
        val script = buildInstallScript(assets, currentUserSid)
        val installed = runElevatedPowerShell(script, timeoutSeconds = 120)
        if (installed.exitCode != 0) return installed
        return waitUntilHealthy(30)
    }

    fun uninstall(): RuntimeCommandResult {
        val assets = currentAssets()
        val script = buildUninstallScript(assets)
        return runElevatedPowerShell(script, timeoutSeconds = 90)
    }

    fun ensureRunning(): RuntimeCommandResult {
        val health = client.health()
        if (health.exitCode == 0) return health
        return install()
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
        runProcess(listOf("sc.exe", "query", GATES_SERVICE_NAME), windowsGatesAppDir(), 15)

    private fun queryLegacyTask(): RuntimeCommandResult =
        runProcess(
            listOf("schtasks.exe", "/Query", "/TN", GATES_RUNTIME_HELPER_TASK_NAME, "/FO", "LIST"),
            windowsGatesAppDir(),
            15,
        )

    private fun queryCurrentUserSid(): String? {
        val result = runProcess(listOf("whoami.exe", "/user", "/fo", "csv", "/nh"), windowsGatesAppDir(), 15)
        if (result.exitCode != 0) return null
        return WINDOWS_SID_PATTERN.find(result.output)?.value
    }

    private fun runElevatedPowerShell(script: String, timeoutSeconds: Int): RuntimeCommandResult {
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charset.forName("UTF-16LE")))
        val wrapper = "\$p = Start-Process -FilePath powershell.exe " +
            "-ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-EncodedCommand','$encoded') " +
            "-Verb RunAs -PassThru -Wait -WindowStyle Hidden; exit \$p.ExitCode"
        return runProcess(
            listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", wrapper),
            windowsGatesAppDir(),
            timeoutSeconds,
        )
    }

    private fun runProcess(args: List<String>, directory: File, timeoutSeconds: Int): RuntimeCommandResult {
        directory.mkdirs()
        val process = ProcessBuilder(args).directory(directory).redirectErrorStream(true).start()
        if (!process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return RuntimeCommandResult(124, "Process timed out.")
        }
        return RuntimeCommandResult(
            process.exitValue(),
            process.inputStream.bufferedReader().use { it.readText() }.trim(),
        )
    }
}

private fun buildInstallScript(assets: WindowsRuntimeServiceAssets, currentUserSid: String): String = """
    ${'$'}ErrorActionPreference = 'Stop'
    ${'$'}serviceDir = Join-Path ${'$'}env:ProgramData 'GATES\Service'
    New-Item -ItemType Directory -Path ${'$'}serviceDir -Force | Out-Null
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
    ${'$'}tokenPath = Join-Path ${'$'}serviceDir 'control.token'
    ${'$'}bytes = New-Object byte[] 32
    ${'$'}rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
    try { ${'$'}rng.GetBytes(${'$'}bytes) } finally { ${'$'}rng.Dispose() }
    ${'$'}token = ([System.BitConverter]::ToString(${'$'}bytes) -replace '-', '').ToLowerInvariant()
    [System.IO.File]::WriteAllText(${'$'}tokenPath, ${'$'}token, [System.Text.Encoding]::ASCII)
    ${'$'}tokenAcl = New-Object System.Security.AccessControl.FileSecurity
    ${'$'}tokenAcl.SetAccessRuleProtection(${'$'}true, ${'$'}false)
    @('S-1-5-18', 'S-1-5-32-544', ${psSingleQuoted(currentUserSid)}) | ForEach-Object {
        ${'$'}identity = New-Object System.Security.Principal.SecurityIdentifier(${'$'}_)
        ${'$'}rule = New-Object System.Security.AccessControl.FileSystemAccessRule(${'$'}identity, 'FullControl', ${'$'}allow)
        ${'$'}tokenAcl.AddAccessRule(${'$'}rule)
    }
    Set-Acl -LiteralPath ${'$'}tokenPath -AclObject ${'$'}tokenAcl
    schtasks.exe /End /TN ${psSingleQuoted(GATES_RUNTIME_HELPER_TASK_NAME)} 2>${'$'}null
    schtasks.exe /Delete /TN ${psSingleQuoted(GATES_RUNTIME_HELPER_TASK_NAME)} /F 2>${'$'}null
    & ${psSingleQuoted(assets.wrapper.absolutePath)} stop 2>${'$'}null
    & ${psSingleQuoted(assets.wrapper.absolutePath)} uninstall 2>${'$'}null
    & ${psSingleQuoted(assets.wrapper.absolutePath)} install
    if (${ '$' }LASTEXITCODE -ne 0) { exit ${ '$' }LASTEXITCODE }
    & ${psSingleQuoted(assets.wrapper.absolutePath)} start
    exit ${ '$' }LASTEXITCODE
""".trimIndent()

private fun buildUninstallScript(assets: WindowsRuntimeServiceAssets?): String = """
    ${'$'}ErrorActionPreference = 'SilentlyContinue'
    ${if (assets == null) "" else "& ${psSingleQuoted(assets.wrapper.absolutePath)} stop; & ${psSingleQuoted(assets.wrapper.absolutePath)} uninstall"}
    schtasks.exe /End /TN ${psSingleQuoted(GATES_RUNTIME_HELPER_TASK_NAME)}
    schtasks.exe /Delete /TN ${psSingleQuoted(GATES_RUNTIME_HELPER_TASK_NAME)} /F
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
private const val PINNED_WINSW_SHA256 =
    "05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da"
private val WINDOWS_SID_PATTERN = Regex("S-1-\\d+(?:-\\d+)+")
