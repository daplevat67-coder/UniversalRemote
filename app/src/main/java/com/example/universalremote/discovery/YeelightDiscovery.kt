package com.example.universalremote.discovery

import com.example.universalremote.model.ControlCapability
import com.example.universalremote.model.NearbyDevice
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class YeelightDiscovery(private val onDevice: (NearbyDevice) -> Unit) {
    @Volatile private var running = false
    private var socket: DatagramSocket? = null

    fun start() {
        stop()
        running = true
        thread(name = "yeelight-discovery") {
            runCatching {
                socket = DatagramSocket().apply { soTimeout = 1200 }
                val query = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1982\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "ST: wifi_bulb\r\n\r\n"
                ).toByteArray(Charsets.UTF_8)
                socket?.send(DatagramPacket(query, query.size, InetAddress.getByName("239.255.255.250"), 1982))
                val buffer = ByteArray(8192)
                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    runCatching { socket?.receive(packet) }.onSuccess {
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val location = header(text, "Location") ?: return@onSuccess
                        if (!location.startsWith("yeelight://", true)) return@onSuccess
                        val hostPort = location.substringAfter("yeelight://")
                        val host = hostPort.substringBefore(':')
                        val port = hostPort.substringAfter(':', "55443").toIntOrNull() ?: 55443
                        val name = header(text, "name")?.takeIf { it.isNotBlank() }
                            ?: header(text, "model")?.let { "Yeelight $it" }
                            ?: "Yeelight"
                        onDevice(
                            NearbyDevice(
                                id = "yeelight:${header(text, "id") ?: host}",
                                name = name,
                                kind = "Лампа / свет",
                                protocol = "Yeelight LAN Control",
                                address = "$host:$port",
                                controllable = true,
                                brand = "Yeelight",
                                capabilities = setOf(ControlCapability.LIGHT_POWER, ControlCapability.BRIGHTNESS, ControlCapability.COLOR),
                                ipAddress = host
                            )
                        )
                    }
                }
            }
        }
    }

    fun stop() { running = false; socket?.close(); socket = null }

    private fun header(text: String, key: String) = text.lineSequence()
        .firstOrNull { it.startsWith("$key:", ignoreCase = true) }
        ?.substringAfter(':')?.trim()
}
