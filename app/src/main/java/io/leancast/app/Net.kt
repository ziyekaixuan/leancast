package io.leancast.app

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** LAN address helpers for composing SSDP LOCATION and description URLs. */
object Net {

    /**
     * LAN IPv4 address, preferring a real Wi-Fi/Ethernet interface. Some
     * firmwares bring up virtual interfaces (Wi-Fi Direct `p2p0`,
     * `dummy0`) that also hold a site-local address — picking the first one
     * in enumeration order can bind SSDP to an address no phone on the LAN
     * can reach, and no later rebind ever fires because the address never
     * "changes".
     */
    fun lanIpv4(): String? = lanInterfaceAddress()?.second

    /** Interface that owns the LAN address, or null when offline. */
    fun lanInterface(): NetworkInterface? = lanInterfaceAddress()?.first

    private fun lanInterfaceAddress(): Pair<NetworkInterface, String>? = runCatching {
        val candidates = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface ->
                iface.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .mapNotNull { addr -> addr.hostAddress?.let { iface to it } }
            }
            .toList()
        candidates.firstOrNull { (iface, _) -> isPhysicalLan(iface) }
            ?: candidates.firstOrNull { (iface, _) -> !isVirtual(iface) }
            ?: candidates.firstOrNull()
    }.getOrNull()

    private fun isPhysicalLan(iface: NetworkInterface): Boolean {
        val name = iface.name
        return name.startsWith("wlan") || name.startsWith("eth")
    }

    private fun isVirtual(iface: NetworkInterface): Boolean {
        val name = iface.name
        return name.startsWith("p2p") || name.startsWith("dummy") ||
            name.startsWith("sit") || name.startsWith("ip6tnl")
    }
}
