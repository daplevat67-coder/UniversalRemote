package com.example.universalremote.discovery

import android.content.Context
import com.example.universalremote.model.ControlCapability
import com.example.universalremote.model.NearbyDevice
import com.example.universalremote.network.LocalEndpointPolicy
import com.example.universalremote.network.WifiNetworkResolver
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import kotlin.concurrent.thread

class YeelightDiscovery(
    context: Context,
    private val onDevice: (NearbyDevice) -> Unit,
    private val onStatus: (String) -> Unit = {}
) {
    private val app = context.applicationContext
    @Volatile private var running = false
    private var socket: DatagramSocket? = null

    fun start() {
        stop()
        val wifi = WifiNetworkResolver.current(app)
        running = true
        thread(name = "yeelight-discovery") {
            runCatching {
                socket = DatagramSocket().apply {
                    soTimeout = 1200
                    if (wifi != null) runCatching { wifi.network.bindSocket(this) }
                }
                val query = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1982\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "ST: wifi_bulb\r\n\r\n"
                ).toByteArray(Charsets.UTF_8)
                socket?.send(DatagramPacket(query, query.size, InetAddress.getByName("239.255.255.250"), 1982))
                onStatus(if (wifi != null) "Yeelight: multicast через Wi-Fi" else "Yeelight: системный multicast fallback")
                val buffer = ByteArray(8192)
                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    runCatching { socket?.receive(packet) }.onSuccess {
                        val sourceHost = packet.address.hostAddress ?: return@onSuccess
                        if (!LocalEndpointPolicy.isPrivateIpv4(sourceHost)) return@onSuccess
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val location = header(text, "Location") ?: return@onSuccess
                        if (!location.startsWith("yeelight://", true)) return@onSuccess
                        val uri = runCatching { URI(location) }.getOrNull() ?: return@onSuccess
                        val advertisedHost = uri.host ?: return@onSuccess
                        val advertisedAddress = runCatching { InetAddress.getByName(advertisedHost) }.getOrNull() ?: return@onSuccess
                        if (advertisedAddress != packet.address) return@onSuccess
                        val port = uri.port.takeIf { p -> p in 1..65535 } ?: 55443
                        val name = header(text, "name")?.takeIf { it.isNotBlank() }
                            ?: header(text, "model")?.let { "Yeelight $it" }
                            ?: "Yeelight"
                        onDevice(
                            NearbyDevice(
                                id = "yeelight:${header(text, "id") ?: sourceHost}",
                                name = name,
                                kind = "Лампа / свет",
                                protocol = "Yeelight LAN Control",
                                address = "$sourceHost:$port",
                                controllable = true,
                                brand = "Yeelight",
                                capabilities = setOf(ControlCapability.LIGHT_POWER, ControlCapability.BRIGHTNESS, ControlCapability.COLOR),
                                ipAddress = sourceHost
                            )
                        )
                    }
                }
            }.onFailure { onStatus("Yeelight: ${it.javaClass.simpleName}") }
        }
    }

    fun stop() { running = false; socket?.close(); socket = null }

    private fun header(text: String, key: String) = text.lineSequence()
        .firstOrNull { it.startsWith("$key:", ignoreCase = true) }
        ?.substringAfter(':')?.trim()
}
