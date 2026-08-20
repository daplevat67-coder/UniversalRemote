package com.example.universalremote.discovery

import com.example.universalremote.model.ControlCapability
import com.example.universalremote.model.NearbyDevice
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class SsdpDiscovery(private val onDevice: (NearbyDevice) -> Unit) {
    @Volatile private var running = false
    private var socket: DatagramSocket? = null

    fun start() {
        stop()
        running = true
        thread(name = "ssdp-discovery") {
            runCatching {
                socket = DatagramSocket().apply { soTimeout = 1300 }
                listOf(
                    "ssdp:all",
                    "urn:schemas-upnp-org:device:MediaRenderer:1",
                    "urn:schemas-upnp-org:service:RenderingControl:1"
                ).forEach(::sendSearch)
                val data = ByteArray(8192)
                while (running) {
                    val packet = DatagramPacket(data, data.size)
                    runCatching { socket?.receive(packet) }.onSuccess {
                        val text = String(packet.data, 0, packet.length)
                        val server = header(text, "SERVER") ?: "UPnP-устройство"
                        val st = header(text, "ST").orEmpty()
                        val usn = header(text, "USN").orEmpty()
                        val location = header(text, "LOCATION")
                        val address = location ?: packet.address.hostAddress.orEmpty()
                        val descriptor = "$server $st $usn".lowercase()
                        val renderer = "mediarenderer" in descriptor || "renderingcontrol" in descriptor
                        val kind = classify(descriptor)
                        val capabilities = if (renderer) setOf(
                            ControlCapability.VOLUME,
                            ControlCapability.MUTE,
                            ControlCapability.MEDIA
                        ) else emptySet()
                        val identity = usn.substringBefore("::").ifBlank { address }
                        onDevice(
                            NearbyDevice(
                                id = "ssdp:$identity",
                                name = friendlyName(server, kind),
                                kind = kind,
                                protocol = if (renderer) "UPnP MediaRenderer" else "SSDP/UPnP",
                                address = address,
                                controllable = renderer,
                                capabilities = capabilities,
                                descriptionUrl = location
                            )
                        )
                    }
                }
            }
        }
    }

    private fun sendSearch(st: String) {
        val query = ("M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n").toByteArray()
        socket?.send(DatagramPacket(query, query.size, InetAddress.getByName("239.255.255.250"), 1900))
    }

    fun stop() { running = false; socket?.close(); socket = null }

    private fun classify(text: String) = when {
        listOf("tv", "television", "mediarenderer", "roku", "bravia", "webos").any { it in text } -> "Телевизор / медиаплеер"
        listOf("speaker", "sonos", "audio", "music").any { it in text } -> "Колонка / медиаплеер"
        listOf("projector", "beamer").any { it in text } -> "Проектор"
        listOf("light", "bulb", "hue", "bridge").any { it in text } -> "Лампа / свет"
        listOf("printer", "ipp").any { it in text } -> "Принтер"
        else -> "Сетевое устройство"
    }

    private fun friendlyName(server: String, kind: String): String =
        server.substringBefore(" UPnP", server).substringBefore(" DLNADOC", server).take(48).ifBlank { kind }

    private fun header(text: String, key: String) = text.lineSequence()
        .firstOrNull { it.startsWith("$key:", ignoreCase = true) }?.substringAfter(':')?.trim()
}
