package com.yokhan.gates.windows.service

import java.nio.file.Path

private const val PRODUCT_DIRECTORY = "GATES"
private const val APP_DIRECTORY = "app"
private const val RESOURCES_DIRECTORY = "resources"
private const val RUNTIME_DIRECTORY = "yokhan-runtime"
private const val WINDOWS_DIRECTORY = "windows"
private const val SING_BOX_EXECUTABLE = "sing-box.exe"
private const val SERVICE_DIRECTORY = "Service"
private const val ACTIVE_CONFIG_FILE = "active.json"
private const val SERVICE_LOG_FILE = "service.log"
private const val CONTROL_TOKEN_FILE = "control.token"

data class WindowsServicePaths(
    val runtimeExecutable: Path,
    val dataDirectory: Path,
    val configFile: Path,
    val logFile: Path,
    val controlTokenFile: Path,
)

object WindowsServicePathContract {

    fun paths(
        programFiles: String = System.getenv("ProgramFiles").orEmpty(),
        programData: String = System.getenv("ProgramData").orEmpty(),
    ): WindowsServicePaths {
        require(programFiles.isNotBlank()) { "ProgramFiles is unavailable" }
        require(programData.isNotBlank()) { "ProgramData is unavailable" }
        val runtimeExecutable = Path.of(
            programFiles,
            PRODUCT_DIRECTORY,
            APP_DIRECTORY,
            RESOURCES_DIRECTORY,
            RUNTIME_DIRECTORY,
            WINDOWS_DIRECTORY,
            SING_BOX_EXECUTABLE,
        )
        val dataDirectory = Path.of(programData, PRODUCT_DIRECTORY, SERVICE_DIRECTORY)
        return WindowsServicePaths(
            runtimeExecutable = runtimeExecutable,
            dataDirectory = dataDirectory,
            configFile = dataDirectory.resolve(ACTIVE_CONFIG_FILE),
            logFile = dataDirectory.resolve(SERVICE_LOG_FILE),
            controlTokenFile = dataDirectory.resolve(CONTROL_TOKEN_FILE),
        )
    }
}
