package com.example.universalremote.network

import android.content.Context
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI

/** Guards LAN endpoints so discovery and control cannot silently escape the selected local network. */
object LocalEndpointPolicy {
    fun isPrivateIpv4(host: String): Boolean {
        val address = literalIpv4(host) ?: return false
        return Ipv4Range.isPrivate(Ipv4Range.ipv4ToInt(address))
    }

    /** True only when [host] is an IPv4 peer inside the physical Wi-Fi/LAN prefix selected by WifiNetworkResolver. */
    fun isInCurrentWifiSubnet(context: Context, host: String): Boolean {
        val target = literalIpv4(host) ?: return false
        if (!Ipv4Range.isPrivate(Ipv4Range.ipv4ToInt(target))) return false
        val lan = WifiNetworkResolver.lanInfo(context) ?: return false
        return samePrefix(lan.ipv4.address, target.address, lan.prefixLength)
    }

    fun requireCurrentWifiSubnet(context: Context, host: String) {
        require(isInCurrentWifiSubnet(context, host)) { "Адрес вне текущей Wi-Fi подсети" }
    }

    /** Validates a discovered URL against the exact literal IPv4 of the advertising device. */
    fun samePrivateHost(expectedHost: String, url: String, allowedSchemes: Set<String>): Boolean = runCatching {
        val expected = literalIpv4(expectedHost) ?: return@runCatching false
        if (!Ipv4Range.isPrivate(Ipv4Range.ipv4ToInt(expected))) return@runCatching false
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase() ?: return@runCatching false
        if (scheme !in allowedSchemes || uri.userInfo != null || uri.host == null) return@runCatching false
        // Avoid DNS rebinding: reject advertised hostnames and require the literal source IP.
        val target = literalIpv4(uri.host) ?: return@runCatching false
        target == expected
    }.getOrDefault(false)

    fun requireSamePrivateHost(expectedHost: String, url: String, allowedSchemes: Set<String>) {
        require(samePrivateHost(expectedHost, url, allowedSchemes)) { "LAN endpoint указывает на другой/публичный хост" }
    }

    // Strict dotted-decimal IPv4 only: never resolve attacker-supplied hostnames.
    private fun literalIpv4(host: String): Inet4Address? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || (part.length > 1 && part[0] == '0') ||
                part.any { it !in '0'..'9' }) return null
            val value = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            bytes[i] = value.toByte()
        }
        return InetAddress.getByAddress(bytes) as Inet4Address
    }

    private fun samePrefix(a: ByteArray, b: ByteArray, prefixLength: Int): Boolean {
        if (a.size != 4 || b.size != 4 || prefixLength !in 0..32) return false
        var bits = prefixLength
        for (i in 0 until 4) {
            if (bits <= 0) return true
            val take = minOf(8, bits)
            val mask = (0xFF shl (8 - take)) and 0xFF
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
            bits -= take
        }
        return true
    }
}
