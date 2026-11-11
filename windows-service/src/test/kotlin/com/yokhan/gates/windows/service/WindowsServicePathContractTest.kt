package com.yokhan.gates.windows.service

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsServicePathContractTest {

    @Test
    fun usesOnlyTheFixedMachineWideWindowsLocations() {
        val paths = WindowsServicePathContract.paths(
            programFiles = "C:\\Program Files",
            programData = "C:\\ProgramData",
        )

        assertEquals(
            "C:\\Program Files\\GATES\\app\\resources\\yokhan-runtime\\windows\\sing-box.exe",
            paths.runtimeExecutable.toString(),
        )
        assertEquals("C:\\Program Files\\GATES\\GATES.exe", paths.launcherExecutable.toString())
        assertEquals("C:\\ProgramData\\GATES\\Service", paths.dataDirectory.toString())
        assertEquals("C:\\ProgramData\\GATES\\Service\\active.json", paths.configFile.toString())
        assertEquals("C:\\ProgramData\\GATES\\Service\\service.log", paths.logFile.toString())
        assertEquals("C:\\ProgramData\\GATES\\Service\\control.token", paths.controlTokenFile.toString())
        assertEquals(
            "C:\\Program Files\\GATES\\app\\resources\\yokhan-runtime\\windows\\xray.exe",
            paths.xrayExecutable.toString(),
        )
        assertEquals("C:\\ProgramData\\GATES\\Service\\xhttp", paths.xhttpDirectory.toString())
    }
}
