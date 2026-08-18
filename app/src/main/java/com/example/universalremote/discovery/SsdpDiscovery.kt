package com.example.universalremote.discovery

import com.example.universalremote.model.NearbyDevice
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class SsdpDiscovery(private val onDevice: (NearbyDevice) -> Unit) {
    @Volatile private var running = false
    private var socket: DatagramSocket? = null

    fun start() {
        running = true
        thread(name = "ssdp-discovery") {
            runCatching {
                socket = DatagramSocket().apply { soTimeout = 1200 }
                val query = ("M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n").toByteArray()
                socket?.send(DatagramPacket(query, query.size, InetAddress.getByName("239.255.255.250"), 1900))
                val data = ByteArray(8192)
                while (running) {
                    val packet = DatagramPacket(data, data.size)
                    runCatching { socket?.receive(packet) }.onSuccess {
                        val text = String(packet.data, 0, packet.length)
                        val server = header(text, "SERVER") ?: header(text, "ST") ?: "UPnP-устройство"
                        val location = header(text, "LOCATION") ?: packet.address.hostAddress.orEmpty()
                        onDevice(NearbyDevice("ssdp:$location", server, "ТВ / медиаплеер / IoT", "SSDP/UPnP", location))
                    }
                }
            }
        }
    }

    fun stop() { running = false; socket?.close(); socket = null }
    private fun header(text: String, key: String) = text.lineSequence()
        .firstOrNull { it.startsWith("$key:", ignoreCase = true) }?.substringAfter(':')?.trim()
}
