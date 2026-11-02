package com.example.universalremote.control

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class WakeOnLanController {
    private val executor = Executors.newSingleThreadExecutor()

    fun wake(mac: String, broadcast: String = "255.255.255.255", callback: (Boolean, String) -> Unit) {
        executor.execute {
            val result = runCatching {
                val macBytes = parseMac(mac) ?: error("Некорректный MAC")
                val payload = ByteArray(6 + 16 * 6)
                repeat(6) { payload[it] = 0xFF.toByte() }
                for (i in 6 until payload.size) payload[i] = macBytes[(i - 6) % 6]
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val address = InetAddress.getByName(broadcast)
                    listOf(7, 9).forEach { port -> socket.send(DatagramPacket(payload, payload.size, address, port)) }
                }
                true to "Wake-on-LAN пакет отправлен"
            }.getOrElse { false to (it.message ?: "Ошибка Wake-on-LAN") }
            callback(result.first, result.second)
        }
    }

    fun close() = executor.shutdownNow()

    private fun parseMac(value: String): ByteArray? {
        val hex = value.replace(":", "").replace("-", "").trim()
        if (!hex.matches(Regex("(?i)[0-9a-f]{12}"))) return null
        return ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
