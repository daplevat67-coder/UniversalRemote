package com.example.universalremote.discovery

import android.content.Context
import com.example.universalremote.model.ControlCapability
import com.example.universalremote.model.NearbyDevice
import com.example.universalremote.network.LocalEndpointPolicy
import com.example.universalremote.network.WifiNetworkResolver
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class SsdpDiscovery(
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
        thread(name = "ssdp-discovery") {
            runCatching {
                socket = DatagramSocket().apply {
                    soTimeout = 1300
                    if (wifi != null) runCatching { wifi.network.bindSocket(this) }
                }
                onStatus(if (wifi != null) "SSDP: поиск через Wi-Fi" else "SSDP: системный multicast fallback")
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
                        val packetHost = packet.address.hostAddress.orEmpty()
                        val rawLocation = header(text, "LOCATION")
                        val location = rawLocation?.takeIf {
                            packetHost.isNotBlank() && LocalEndpointPolicy.samePrivateHost(packetHost, it, setOf("http", "https"))
                        }
                        val address = location ?: packetHost
                        val descriptor = "$server $st $usn".lowercase()
                        val renderer = "mediarenderer" in descriptor || "renderingcontrol" in descriptor
                        val roku = "roku" in descriptor
                        val samsung = "samsung" in descriptor || "tizen" in descriptor
                        val webos = "webos" in descriptor || ("lg" in descriptor && "tv" in descriptor)
                        val kind = classify(descriptor)
                        val capabilities = when {
                            roku || samsung || webos -> setOf(
                                ControlCapability.POWER, ControlCapability.VOLUME, ControlCapability.MUTE,
                                ControlCapability.MEDIA, ControlCapability.NAVIGATION, ControlCapability.CHANNEL, ControlCapability.INPUT
                            )
                            renderer -> setOf(ControlCapability.VOLUME, ControlCapability.MUTE, ControlCapability.MEDIA)
                            else -> emptySet()
                        }
                        val protocol = when {
                            roku -> "Roku ECP"
                            samsung -> "Samsung Tizen WebSocket"
                            webos -> "LG webOS SSAP"
                            renderer -> "UPnP MediaRenderer"
                            else -> "SSDP/UPnP"
                        }
                        val identity = usn.substringBefore("::").ifBlank { address }
                        val host = packetHost
                        onDevice(
                            NearbyDevice(
                                id = "ssdp:$identity",
                                name = friendlyName(server, kind),
                                kind = kind,
                                protocol = protocol,
                                address = address,
                                controllable = roku || samsung || webos || (renderer && location != null),
                                brand = when { roku -> "Roku"; samsung -> "Samsung"; webos -> "LG"; else -> null },
                                capabilities = capabilities,
                                descriptionUrl = location,
                                ipAddress = host.takeIf { it.isNotBlank() }
                            )
                        )
                    }
                }
            }.onFailure { onStatus("SSDP: ${it.javaClass.simpleName}") }
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
