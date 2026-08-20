package com.example.universalremote.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI

/** Guards LAN endpoints so discovery and control cannot silently escape the selected local network. */
object LocalEndpointPolicy {
    fun isPrivateIpv4(host: String): Boolean {
        val address = resolveIpv4(host) ?: return false
        return Ipv4Range.isPrivate(Ipv4Range.ipv4ToInt(address))
    }

    /** True only when [host] is an IPv4 peer inside the active Wi-Fi link prefix. */
    fun isInCurrentWifiSubnet(context: Context, host: String): Boolean {
        val target = resolveIpv4(host) ?: return false
        if (!Ipv4Range.isPrivate(Ipv4Range.ipv4ToInt(target))) return false
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val wifiNetworks = cm.allNetworks.filter { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        return wifiNetworks.any networkLoop@ { network ->
            val props = cm.getLinkProperties(network) ?: return@networkLoop false
            props.linkAddresses.any linkLoop@ { link ->
                val local = link.address as? Inet4Address ?: return@linkLoop false
                samePrefix(local.address, target.address, link.prefixLength)
            }
        }
    }

    fun requireCurrentWifiSubnet(context: Context, host: String) {
        require(isInCurrentWifiSubnet(context, host)) { "Адрес вне текущей Wi-Fi подсети" }
    }

    fun samePrivateHost(expectedHost: String, url: String, allowedSchemes: Set<String>): Boolean = runCatching {
        val expected = resolveIpv4(expectedHost) ?: return@runCatching false
        if (!Ipv4Range.isPrivate(Ipv4Range.ipv4ToInt(expected))) return@runCatching false
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase() ?: return@runCatching false
        if (scheme !in allowedSchemes || uri.userInfo != null) return@runCatching false
        val targetHost = uri.host ?: return@runCatching false
        val targets = InetAddress.getAllByName(targetHost).filterIsInstance<Inet4Address>()
        targets.isNotEmpty() && targets.all { Ipv4Range.isPrivate(Ipv4Range.ipv4ToInt(it)) } && targets.any { it == expected }
    }.getOrDefault(false)

    fun requireSamePrivateHost(expectedHost: String, url: String, allowedSchemes: Set<String>) {
        require(samePrivateHost(expectedHost, url, allowedSchemes)) { "LAN endpoint указывает на другой/публичный хост" }
    }

    private fun resolveIpv4(host: String): Inet4Address? = runCatching {
        InetAddress.getAllByName(host).filterIsInstance<Inet4Address>().singleOrNull()
            ?: InetAddress.getAllByName(host).filterIsInstance<Inet4Address>().firstOrNull()
    }.getOrNull()

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
