package com.example.universalremote.control

import android.content.Context
import com.example.universalremote.network.Ipv4Range
import com.example.universalremote.network.WifiNetworkResolver
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class WakeOnLanController(context: Context) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()

    fun wake(mac: String, broadcast: String = "255.255.255.255", callback: (Boolean, String) -> Unit) {
        executor.execute {
            val result = runCatching {
                val macBytes = parseMac(mac) ?: error("Некорректный MAC")
                val lan = WifiNetworkResolver.lanInfo(appContext) ?: error("Нет активной Wi-Fi/LAN сети")
                val physicalWifi = lan.network ?: error("Не удалось привязать Wake-on-LAN к физической Wi-Fi сети")
                val ip = Ipv4Range.ipv4ToInt(lan.ipv4)
                val mask = -1 shl (32 - lan.prefixLength)
                val localBroadcast = Ipv4Range.intToIpv4((ip and mask) or mask.inv()).hostAddress
                require(broadcast == "255.255.255.255" || broadcast == localBroadcast) {
                    "Wake-on-LAN разрешён только в текущей Wi-Fi подсети"
                }

                val payload = ByteArray(6 + 16 * 6)
                repeat(6) { payload[it] = 0xFF.toByte() }
                for (i in 6 until payload.size) payload[i] = macBytes[(i - 6) % 6]
                DatagramSocket().use { socket ->
                    physicalWifi.bindSocket(socket)
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
