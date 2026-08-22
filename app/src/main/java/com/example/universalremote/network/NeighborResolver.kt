package com.example.universalremote.network

import java.io.ByteArrayOutputStream
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
        val text = readProcessBounded(
            listOf("/system/bin/ip", "neigh", "show"),
            maxBytes = 256 * 1024,
            timeoutMs = 800
        ) ?: return@runCatching emptyMap()
        text.lineSequence().mapNotNull { line ->
            val ip = ipv4Regex.find(line)?.value ?: return@mapNotNull null
            val mac = macRegex.find(line)?.value ?: return@mapNotNull null
            if (mac == "00:00:00:00:00:00") null else ip to mac.lowercase()
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun readIpNeigh(ip: String): String? = runCatching {
        val text = readProcessBounded(
            listOf("/system/bin/ip", "neigh", "show", ip),
            maxBytes = 16 * 1024,
            timeoutMs = 500
        ) ?: return@runCatching null
        macRegex.find(text)?.value?.lowercase()
    }.getOrNull()

    /** Reads stdout concurrently with a hard byte cap so process timeout cannot be defeated by a blocked readText(). */
    private fun readProcessBounded(command: List<String>, maxBytes: Int, timeoutMs: Long): String? {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val reader = Thread {
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    var remaining = maxBytes
                    while (remaining > 0) {
                        val n = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        remaining -= n
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "uremote-ip-neigh-reader"
            start()
        }

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(150)
        if (reader.isAlive) {
            runCatching { process.inputStream.close() }
            reader.interrupt()
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
