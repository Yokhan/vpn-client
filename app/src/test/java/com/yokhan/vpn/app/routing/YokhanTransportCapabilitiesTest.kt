package com.yokhan.vpn.app.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YokhanTransportCapabilitiesTest {

    @Test
    fun sidecarIsDisabledWhenTheBuildFlagIsOff() {
        val capability = YokhanTransportCapabilities.xhttp(
            primaryAbi = "arm64-v8a",
            enabledInBuild = false,
            binaryState = XraySidecarBinaryState.Executable,
        )

        assertEquals(YokhanTransportAvailability.DisabledInBuild, capability.availability)
        assertFalse(capability.available)
    }

    @Test
    fun unsupportedAbiIsReportedEvenWhenTheBinaryExists() {
        val capability = YokhanTransportCapabilities.xhttp(
            primaryAbi = "armeabi-v7a",
            enabledInBuild = true,
            binaryState = XraySidecarBinaryState.Executable,
        )

        assertEquals(YokhanTransportAvailability.UnsupportedAbi, capability.availability)
    }

    @Test
    fun everyBinaryStateMapsToAnExplicitReason() {
        val cases = mapOf(
            XraySidecarBinaryState.Missing to YokhanTransportAvailability.BinaryMissing,
            XraySidecarBinaryState.NotExecutable to YokhanTransportAvailability.BinaryNotExecutable,
            XraySidecarBinaryState.HashMismatch to YokhanTransportAvailability.BinaryHashMismatch,
            XraySidecarBinaryState.AssetOnly to YokhanTransportAvailability.AssetOnlyUnsupported,
        )

        cases.forEach { (binaryState, expected) ->
            val capability = YokhanTransportCapabilities.xhttp("arm64-v8a", true, binaryState)
            assertEquals(expected, capability.availability)
            assertTrue(capability.summary.isNotBlank())
        }
    }

    @Test
    fun xhttpIsAvailableOnlyWhenAllFactsAreTrue() {
        val capability = YokhanTransportCapabilities.xhttp(
            primaryAbi = "arm64-v8a",
            enabledInBuild = true,
            binaryState = XraySidecarBinaryState.Executable,
        )

        assertEquals(YokhanTransportAvailability.Available, capability.availability)
        assertTrue(capability.available)
    }

    @Test
    fun x86_64IsTheOnlyOtherSupportedAndroidAbi() {
        assertTrue("x86_64" in YokhanTransportCapabilities.XHTTP_SUPPORTED_ABIS)
        assertFalse("x86" in YokhanTransportCapabilities.XHTTP_SUPPORTED_ABIS)
        assertFalse("armeabi-v7a" in YokhanTransportCapabilities.XHTTP_SUPPORTED_ABIS)
    }
}
