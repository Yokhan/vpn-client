package com.yokhan.gates.windows

import java.io.File

internal const val GATES_RUNTIME_HELPER_TASK_NAME = "GATES Runtime Helper"

internal fun windowsGatesAppDir(): File {
    val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { File(it) }
        ?: File(System.getProperty("user.home"), "AppData/Roaming")
    return File(appData, "GATES")
}
