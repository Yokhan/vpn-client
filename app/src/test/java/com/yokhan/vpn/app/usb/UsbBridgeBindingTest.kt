package com.yokhan.gates.app.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsbBridgeBindingTest {
    @Test
    fun selectsPrivateIpv4FromRndisInterface() {
        val selected = UsbBridgeBinding.selectUsbIpv4(
            listOf(
                UsbInterfaceCandidate("wlan0", listOf("192.168.31.168")),
                UsbInterfaceCandidate("rndis0", listOf("192.168.42.129")),
            ),
        )

        assertEquals("192.168.42.129", selected)
    }

    @Test
    fun acceptsUsbAndNcmInterfaceNames() {
        assertEquals(
            "172.20.10.1",
            UsbBridgeBinding.selectUsbIpv4(
                listOf(UsbInterfaceCandidate("usb0", listOf("172.20.10.1"))),
            ),
        )
        assertEquals(
            "10.0.0.1",
            UsbBridgeBinding.selectUsbIpv4(
                listOf(UsbInterfaceCandidate("ncm0", listOf("10.0.0.1"))),
            ),
        )
    }

    @Test
    fun ignoresWifiMobileAndUnsafeAddresses() {
        val selected = UsbBridgeBinding.selectUsbIpv4(
            listOf(
                UsbInterfaceCandidate("wlan0", listOf("192.168.31.168")),
                UsbInterfaceCandidate("ccmni0", listOf("10.1.2.3")),
                UsbInterfaceCandidate("rndis0", listOf("0.0.0.0", "169.254.2.1")),
            ),
        )

        assertNull(selected)
    }

    @Test
    fun usbModeNeverBindsToAllNetworkInterfaces() {
        val bindings = UsbBridgeBinding.resolveBindAddresses(
            isUsbBridgeEnabled = true,
            usbIpv4 = "192.168.42.129",
            isLanAccessAllowed = true,
            isStealthMode = false,
        )

        assertEquals(listOf("127.0.0.1", "192.168.42.129"), bindings)
    }

    @Test
    fun missingUsbInterfaceFallsBackToLocalhostOnly() {
        val bindings = UsbBridgeBinding.resolveBindAddresses(
            isUsbBridgeEnabled = true,
            usbIpv4 = null,
            isLanAccessAllowed = true,
            isStealthMode = false,
        )

        assertEquals(listOf("127.0.0.1"), bindings)
    }
}
