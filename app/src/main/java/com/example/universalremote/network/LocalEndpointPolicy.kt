package com.example.universalremote.network

import android.content.Context
import android.net.Network
import java.net.Inet4Address
import java.net.URI

/** Guards LAN endpoints so discovery and control cannot silently escape the selected local network. */
object LocalEndpointPolicy {
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Legacy call-site name kept for compatibility. The host must be a literal private IPv4 and,
     * once the application is initialized, it must also belong to the current physical Wi-Fi/LAN prefix.
     * Hostnames are intentionally rejected so DNS cannot change the peer after validation.
     */
    fun isPrivateIpv4(host: String): Boolean {
        val address = literalIpv4(host) ?: return false
        if (!Ipv4Range.isPrivate(Ipv4Range.ipv4ToInt(address))) return false
        val context = appContext ?: return true
        val lan = WifiNetworkResolver.lanInfo(context) ?: return false
        return samePrefix(lan.ipv4.address, address.address, lan.prefixLength)
    }

    /** True only when [host] is a literal IPv4 peer inside the physical Wi-Fi/LAN prefix. */
    fun isInCurrentWifiSubnet(context: Context, host: String): Boolean {
        val target = literalIpv4(host) ?: return false
        if (!Ipv4Range.isPrivate(Ipv4Range.ipv4ToInt(target))) return false
        val lan = WifiNetworkResolver.lanInfo(context.applicationContext) ?: return false
        return samePrefix(lan.ipv4.address, target.address, lan.prefixLength)
    }

    fun requireCurrentWifiSubnet(context: Context, host: String) {
        require(isInCurrentWifiSubnet(context, host)) { "Адрес вне текущей физической Wi-Fi/LAN подсети" }
    }

    fun requireCurrentWifiSubnet(host: String) {
        val context = appContext ?: error("LAN policy не инициализирован")
        requireCurrentWifiSubnet(context, host)
    }

    /** Physical Wi-Fi Network when Android exposes one; null on OEM fallback paths. */
    fun currentNetwork(context: Context): Network? = WifiNetworkResolver.lanInfo(context.applicationContext)?.network
    fun currentNetwork(): Network? = appContext?.let { WifiNetworkResolver.lanInfo(it)?.network }

    /**
     * Discovery-controlled URLs must point to the exact literal source IPv4. Hostnames are rejected.
     * This closes multi-A DNS rebinding / TOCTOU between validation and the real HTTP/WebSocket connect.
     */
    fun samePrivateHost(expectedHost: String, url: String, allowedSchemes: Set<String>): Boolean = runCatching {
        val expected = literalIpv4(expectedHost) ?: return@runCatching false
        if (!isPrivateIpv4(expectedHost)) return@runCatching false
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase() ?: return@runCatching false
        if (scheme !in allowedSchemes || uri.userInfo != null) return@runCatching false
        val targetHost = uri.host ?: return@runCatching false
        val target = literalIpv4(targetHost) ?: return@runCatching false
        target == expected && isPrivateIpv4(targetHost)
    }.getOrDefault(false)

    fun requireSamePrivateHost(expectedHost: String, url: String, allowedSchemes: Set<String>) {
        require(samePrivateHost(expectedHost, url, allowedSchemes)) { "LAN endpoint обязан указывать на исходный literal IPv4" }
    }

    private fun literalIpv4(host: String): Inet4Address? {
        val value = Ipv4Range.parseIp(host.trim()) ?: return null
        return Ipv4Range.intToIpv4(value) as? Inet4Address
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
