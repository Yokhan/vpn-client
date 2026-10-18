package com.yokhan.gates.windows

import java.io.File

internal val WINDOWS_ACCESS_ONLY_COMMANDS = setOf("login", "import-app", "import-sub")

internal fun windowsAccessAcceptanceController(directory: File): WindowsRuntimeController =
    WindowsRuntimeController(
        allowRuntimeServiceDelegation = false,
        appDir = directory,
        accessOnly = true,
        runtimeService = object : WindowsRuntimeControl {
            override fun health(): RuntimeCommandResult = error("Runtime forbidden in access acceptance")
            override fun startTunnel(configJson: String): RuntimeCommandResult = error("Runtime forbidden in access acceptance")
            override fun stop(): RuntimeCommandResult = error("Runtime forbidden in access acceptance")
            override fun configurationDigest(): RuntimeCommandResult = error("Runtime forbidden in access acceptance")
        },
        prepareRuntimeService = { error("Service preparation forbidden in access acceptance") },
        prepareRuntimeWithoutTunnel = { error("Service preparation forbidden in access acceptance") },
    )
