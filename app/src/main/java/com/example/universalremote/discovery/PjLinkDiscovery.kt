package com.example.universalremote.discovery

import android.content.Context
import android.net.LinkProperties
import com.example.universalremote.model.ControlCapability
import com.example.universalremote.model.NearbyDevice
import com.example.universalremote.network.LocalEndpointPolicy
import com.example.universalremote.network.TcpProbe
import com.example.universalremote.network.WifiNetworkResolver
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.concurrent.thread

/** PJLink Class 2 discovery: UDP 4352, %2SRCH -> %2ACKN=<MAC>. */
class PjLinkDiscovery(
    context: Context,
    private val onDevice: (NearbyDevice) -> Unit,
    private val onStatus: (String) -> Unit = {}
) {
    private val app = context.applicationContext
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private val mac12 = Regex("(?i)^[0-9a-f]{12}$")
    private val macColon = Regex("(?i)^(?:[0-9a-f]{2}:){5}[0-9a-f]{2}$")

    fun start() {
        stop()
        val wifi = WifiNetworkResolver.current(app)
        running = true
        thread(name = "pjlink-discovery") {
            val localSocket = runCatching {
                DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 1100
                    if (wifi != null) runCatching { wifi.network.bindSocket(this) }
                }
            }.getOrNull() ?: return@thread
            socket = localSocket
            runCatching {
                val broadcastAddress = wifi?.let { currentBroadcast(it.linkProperties) } ?: InetAddress.getByName("255.255.255.255")
                val command = "%2SRCH\r".toByteArray(Charsets.US_ASCII)
                localSocket.send(DatagramPacket(command, command.size, broadcastAddress, 4352))
                onStatus(if (wifi != null) "PJLink: broadcast через Wi-Fi" else "PJLink: global broadcast fallback")
                val buffer = ByteArray(512)
                val deadline = System.currentTimeMillis() + 12_000L
                while (running && socket === localSocket && System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    runCatching { localSocket.receive(packet) }.onSuccess {
                        val response = String(packet.data, 0, packet.length, Charsets.US_ASCII).trim()
                        if (!response.startsWith("%2ACKN=", ignoreCase = true)) return@onSuccess
                        val host = packet.address.hostAddress ?: return@onSuccess
                        if (!LocalEndpointPolicy.isInCurrentWifiSubnet(app, host)) {
                            onStatus("PJLink: ответ вне текущей Wi-Fi подсети отклонён")
                            return@onSuccess
                        }
                        val rawMac = response.substringAfter('=').trim()
                        val normalizedMac = normalizeMac(rawMac)
                        if (rawMac.isNotBlank() && normalizedMac == null) {
                            onStatus("PJLink: ACKN с некорректным MAC отклонён")
                            return@onSuccess
                        }
                        val verified = TcpProbe.isOpen(wifi?.network, host, 4352, 450)
                        onDevice(
                            NearbyDevice(
                                id = "pjlink:${normalizedMac ?: host}",
                                name = "PJLink проектор",
                                kind = "Проектор",
                                protocol = if (verified) "PJLink Class 2 • TCP verified" else "PJLink Class 2 • кандидат",
                                address = host,
                                controllable = verified,
                                capabilities = if (verified) setOf(ControlCapability.POWER, ControlCapability.VOLUME, ControlCapability.MUTE) else emptySet(),
                                ipAddress = host,
                                macAddress = normalizedMac,
                                verified = verified
                            )
                        )
                    }
                }
            }.onFailure { onStatus("PJLink: ${it.javaClass.simpleName}") }
            runCatching { localSocket.close() }
            if (socket === localSocket) {
                socket = null
                running = false
            }
        }
    }

    fun stop() { running = false; socket?.close(); socket = null }

    private fun normalizeMac(value: String): String? = when {
        macColon.matches(value) -> value.lowercase()
        mac12.matches(value) -> value.chunked(2).joinToString(":").lowercase()
        else -> null
    }

    private fun currentBroadcast(props: LinkProperties): InetAddress? {
        val link = props.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val bytes = link.address.address
        var ip = 0
        bytes.forEach { ip = (ip shl 8) or (it.toInt() and 0xff) }
        val prefix = link.prefixLength.coerceIn(0, 32)
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val bcast = ip or mask.inv()
        return InetAddress.getByAddress(byteArrayOf(
            (bcast ushr 24).toByte(), (bcast ushr 16).toByte(), (bcast ushr 8).toByte(), bcast.toByte()
        ))
    }
}
