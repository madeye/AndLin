package tech.anl.library.utils

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkAddresses {
    /** The device's first site-local IPv4 address (Wi-Fi or Ethernet), or null when offline. */
    fun lanAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (err: Exception) {
        null
    }
}
