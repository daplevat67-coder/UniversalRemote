package com.example.universalremote.network

import java.io.File

object NeighborResolver {
    private val macRegex = Regex("(?i)\\b([0-9a-f]{2}:){5}[0-9a-f]{2}\\b")

    fun macFor(ip: String): String? {
        readProcArp(ip)?.let { return it }
        return readIpNeigh(ip)
    }

    private fun readProcArp(ip: String): String? = runCatching {
        val file = File("/proc/net/arp")
        if (!file.canRead()) return null
        file.useLines { lines ->
            lines.drop(1).mapNotNull { line ->
                val cols = line.trim().split(Regex("\\s+"))
                if (cols.size >= 4 && cols[0] == ip) cols[3].takeIf { macRegex.matches(it) && it != "00:00:00:00:00:00" } else null
            }.firstOrNull()
        }
    }.getOrNull()

    private fun readIpNeigh(ip: String): String? = runCatching {
        val process = ProcessBuilder("/system/bin/ip", "neigh", "show", ip).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        macRegex.find(text)?.value
    }.getOrNull()
}
