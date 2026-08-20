package com.example.universalremote.network

import java.net.Inet4Address
import java.net.InetAddress

object Ipv4Range {
    data class Parsed(val first: Int, val last: Int, val label: String) {
        val size: Int get() = (last.toLong() - first.toLong() + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun fromCidr(cidr: String): Parsed? {
        val parts = cidr.trim().split('/')
        if (parts.size != 2) return null
        val ip = parseIp(parts[0]) ?: return null
        val prefix = parts[1].toIntOrNull()?.takeIf { it in 0..32 } ?: return null
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val network = ip and mask
        val broadcast = network or mask.inv()
        val first = if (prefix <= 30) network + 1 else network
        val last = if (prefix <= 30) broadcast - 1 else broadcast
        if (first > last) return null
        return Parsed(first, last, "$cidr")
    }

    fun fromDashRange(text: String): Parsed? {
        val parts = text.trim().split('-', limit = 2)
        if (parts.size != 2) return null
        val first = parseIp(parts[0].trim()) ?: return null
        val last = parseIp(parts[1].trim()) ?: return null
        if (first.toUInt() > last.toUInt()) return null
        return Parsed(first, last, text.trim())
    }

    fun parse(text: String): Parsed? = when {
        '/' in text -> fromCidr(text)
        '-' in text -> fromDashRange(text)
        else -> null
    }

    fun parseIp(text: String): Int? = runCatching {
        val address = InetAddress.getByName(text.trim())
        if (address !is Inet4Address) return null
        ipv4ToInt(address)
    }.getOrNull()

    fun ipv4ToInt(address: Inet4Address): Int {
        val b = address.address
        return ((b[0].toInt() and 0xff) shl 24) or
            ((b[1].toInt() and 0xff) shl 16) or
            ((b[2].toInt() and 0xff) shl 8) or
            (b[3].toInt() and 0xff)
    }

    fun intToIpv4(value: Int): InetAddress = InetAddress.getByAddress(
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        )
    )

    fun isPrivate(value: Int): Boolean {
        val a = (value ushr 24) and 0xff
        val b = (value ushr 16) and 0xff
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
    }

    fun isPrivate(range: Parsed): Boolean = isPrivate(range.first) && isPrivate(range.last)
}
