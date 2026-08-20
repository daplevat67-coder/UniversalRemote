package com.example.universalremote.network

import com.example.universalremote.model.PortService
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket

object TcpProbe {
    private val names = mapOf(
        20 to "FTP-data", 21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP",
        53 to "DNS", 80 to "HTTP", 110 to "POP3", 139 to "NetBIOS", 143 to "IMAP",
        443 to "HTTPS", 445 to "SMB", 554 to "RTSP", 631 to "IPP", 1883 to "MQTT",
        3000 to "LG webOS SSAP", 3001 to "LG webOS SSAP TLS", 3389 to "RDP", 4352 to "PJLink",
        5000 to "HTTP/UPnP", 5001 to "HTTPS/UPnP", 6466 to "Android TV Remote", 6467 to "Android TV Pairing",
        8001 to "Samsung Tizen Remote", 8002 to "Samsung Tizen Remote TLS", 8008 to "Google Cast HTTP",
        8009 to "Google Cast", 8060 to "Roku ECP", 8080 to "HTTP-alt", 8443 to "HTTPS-alt",
        8883 to "MQTT TLS", 9100 to "JetDirect", 55443 to "Yeelight LAN Control"
    )

    fun isOpen(host: String, port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    }.getOrDefault(false)

    fun inspect(host: String, port: Int, connectTimeoutMs: Int, bannerTimeoutMs: Int): PortService? = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = bannerTimeoutMs
            val service = names[port] ?: "TCP"
            val banner = runCatching {
                when (port) {
                    80, 5000, 8008, 8080 -> httpBanner(socket, host)
                    22, 21, 23, 25, 110, 143 -> passiveBanner(socket)
                    else -> passiveBanner(socket)
                }
            }.getOrNull()
            PortService(port, service, banner)
        }
    }.getOrNull()

    private fun httpBanner(socket: Socket, host: String): String? {
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write("HEAD / HTTP/1.0\r\nHost: $host\r\nUser-Agent: UniversalRemote-Lab/0.4\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
        return readPrintable(socket).lineSequence()
            .filter { it.startsWith("HTTP/", true) || it.startsWith("Server:", true) || it.startsWith("WWW-Authenticate:", true) }
            .take(4).joinToString(" | ").takeIf { it.isNotBlank() }
    }

    private fun passiveBanner(socket: Socket): String? = readPrintable(socket).lineSequence().firstOrNull()?.take(180)

    private fun readPrintable(socket: Socket): String {
        val input = BufferedInputStream(socket.getInputStream())
        val buffer = ByteArray(1024)
        val read = input.read(buffer)
        if (read <= 0) return ""
        return String(buffer, 0, read, Charsets.ISO_8859_1)
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(500)
    }

    fun serviceName(port: Int): String = names[port] ?: "TCP"
}
