package com.yokhan.gates.windows

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsRuntimeLauncherTest {
    @Test
    fun recognizesOnlyTheTwoSupportedServiceArgumentForms() {
        assertEquals(
            GatesRuntimeLauncherCommand.SERVICE,
            classifyGatesRuntimeLauncherArgs(listOf("--runtime-service")),
        )
        assertEquals(
            GatesRuntimeLauncherCommand.CLEANUP_PROTECTION,
            classifyGatesRuntimeLauncherArgs(listOf("--runtime-service", "--cleanup-protection")),
        )
        assertEquals(
            GatesRuntimeLauncherCommand.INVALID,
            classifyGatesRuntimeLauncherArgs(listOf("--runtime-service", "--unexpected")),
        )
        assertEquals(
            GatesRuntimeLauncherCommand.NOT_SERVICE,
            classifyGatesRuntimeLauncherArgs(listOf("--runtime-service-extra")),
        )
    }
}
