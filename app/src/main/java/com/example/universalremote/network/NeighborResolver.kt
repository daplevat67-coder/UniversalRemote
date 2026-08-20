package com.example.universalremote.network

import java.io.File
import java.util.concurrent.TimeUnit

object NeighborResolver {
    private val macRegex = Regex("(?i)\\b([0-9a-f]{2}:){5}[0-9a-f]{2}\\b")
    private val ipv4Regex = Regex("\\b(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}\\b")

    /** One cached-style read for a whole scan instead of spawning `ip neigh` once per candidate IP. */
    fun snapshot(): Map<String, String> {
        val out = linkedMapOf<String, String>()
        readProcArpAll().forEach { (ip, mac) -> out[ip] = mac }
        readIpNeighAll().forEach { (ip, mac) -> out[ip] = mac }
        return out
    }

    fun macFor(ip: String): String? {
        readProcArpAll()[ip]?.let { return it }
        return readIpNeigh(ip)
    }

    private fun readProcArpAll(): Map<String, String> = runCatching {
        val file = File("/proc/net/arp")
        if (!file.canRead()) return emptyMap()
        file.useLines { lines ->
            lines.drop(1).mapNotNull { line ->
                val cols = line.trim().split(Regex("\\s+"))
                if (cols.size < 4) return@mapNotNull null
                val ip = cols[0]
                val mac = cols[3]
                if (ipv4Regex.matches(ip) && macRegex.matches(mac) && mac != "00:00:00:00:00:00") ip to mac.lowercase() else null
            }.toMap()
        }
    }.getOrDefault(emptyMap())

    private fun readIpNeighAll(): Map<String, String> = runCatching {
        val process = ProcessBuilder("/system/bin/ip", "neigh", "show").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText().take(256 * 1024) }
        if (!process.waitFor(800, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        text.lineSequence().mapNotNull { line ->
            val ip = ipv4Regex.find(line)?.value ?: return@mapNotNull null
            val mac = macRegex.find(line)?.value ?: return@mapNotNull null
            if (mac == "00:00:00:00:00:00") null else ip to mac.lowercase()
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun readIpNeigh(ip: String): String? = runCatching {
        val process = ProcessBuilder("/system/bin/ip", "neigh", "show", ip).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText().take(16 * 1024) }
        if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        macRegex.find(text)?.value?.lowercase()
    }.getOrNull()
}
