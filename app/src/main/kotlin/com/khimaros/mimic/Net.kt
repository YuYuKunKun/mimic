package com.khimaros.mimic

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

// helpers for choosing and presenting the http/mcp bind interface. the surfaces
// bind loopback by default; the user may instead pick a specific lan address or
// 0.0.0.0 (all interfaces) to reach the device from another machine.
object Net {
    const val LOOPBACK = "127.0.0.1"
    const val ALL = "0.0.0.0"

    // up, non-loopback ipv4 addresses paired with their interface name.
    fun lanAddresses(): List<Pair<String, String>> = try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni ->
                Collections.list(ni.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .map { (it.hostAddress ?: "") to ni.name }
            }
            .filter { it.first.isNotEmpty() }
    } catch (_: Exception) {
        emptyList()
    }

    // a concrete host a peer could actually reach for a given bind selection
    // (0.0.0.0 has no single address, so surface the first lan ip).
    fun displayHost(bind: String): String =
        if (bind == ALL) lanAddresses().firstOrNull()?.first ?: ALL else bind

    fun isLoopback(bind: String): Boolean = bind == LOOPBACK
}
