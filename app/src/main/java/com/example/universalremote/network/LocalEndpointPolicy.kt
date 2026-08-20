package com.example.universalremote.network

import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI

/** Guards URLs learned from LAN devices so they cannot redirect control traffic off-host. */
object LocalEndpointPolicy {
    fun isPrivateIpv4(host: String): Boolean {
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet4Address ?: return false
        return Ipv4Range.isPrivate(Ipv4Range.ipv4ToInt(address))
    }

    fun samePrivateHost(expectedHost: String, url: String, allowedSchemes: Set<String>): Boolean = runCatching {
        val expected = InetAddress.getByName(expectedHost) as? Inet4Address ?: return@runCatching false
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
}
