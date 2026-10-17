package com.yokhan.gates.app.usb

import java.net.Inet4Address
import java.net.NetworkInterface

data class UsbInterfaceCandidate(
    val name: String,
    val ipv4Addresses: List<String>,
)

object UsbBridgeBinding {
    private val usbInterfaceMarkers = listOf("rndis", "usb", "ncm")

    fun detectUsbIpv4(): String? {
        val candidates = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .map { network ->
                    UsbInterfaceCandidate(
                        name = network.name,
                        ipv4Addresses = network.inetAddresses.toList()
                            .filterIsInstance<Inet4Address>()
                            .filter { it.isSiteLocalAddress }
                            .map { it.hostAddress.orEmpty() },
                    )
                }
        }.getOrDefault(emptyList())
        return selectUsbIpv4(candidates)
    }

    fun selectUsbIpv4(candidates: List<UsbInterfaceCandidate>): String? {
        return candidates.asSequence()
            .filter { candidate -> isUsbInterfaceName(candidate.name) }
            .flatMap { candidate -> candidate.ipv4Addresses.asSequence() }
            .firstOrNull(::isUsableIpv4)
    }

    fun resolveBindAddresses(
        isUsbBridgeEnabled: Boolean,
        usbIpv4: String?,
        isLanAccessAllowed: Boolean,
        isStealthMode: Boolean,
    ): List<String> {
        if (isUsbBridgeEnabled) {
            return listOfNotNull(LOCALHOST, usbIpv4?.takeIf(::isUsableIpv4)).distinct()
        }
        return listOf(if (isLanAccessAllowed && !isStealthMode) WILDCARD_IPV4 else LOCALHOST)
    }

    private fun isUsbInterfaceName(name: String): Boolean {
        val normalized = name.lowercase()
        return usbInterfaceMarkers.any(normalized::contains)
    }

    private fun isUsableIpv4(address: String): Boolean {
        return address.isNotBlank() &&
            address != "0.0.0.0" &&
            address != "127.0.0.1" &&
            !address.startsWith("169.254.")
    }
}

private const val LOCALHOST = "127.0.0.1"
private const val WILDCARD_IPV4 = "0.0.0.0"
