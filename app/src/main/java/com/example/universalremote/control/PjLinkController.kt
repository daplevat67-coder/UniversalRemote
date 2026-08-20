package com.example.universalremote.control

import com.example.universalremote.network.BoundedIo
import com.example.universalremote.network.LocalEndpointPolicy
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

class PjLinkController {
    data class Result(val ok: Boolean, val message: String)
    private val executor = Executors.newFixedThreadPool(3)
    private val random = SecureRandom()

    fun probe(host: String, callback: (Result) -> Unit) = executor.execute {
        val result = runCatching {
            connect(host).use { socket ->
                val greeting = BoundedIo.readAsciiLine(socket.getInputStream(), MAX_LINE).orEmpty()
                if (!greeting.startsWith("PJLINK ")) error("Ответ не похож на PJLink")
                Result(true, if (greeting.startsWith("PJLINK 1")) "PJLink доступен • требуется пароль" else "PJLink доступен")
            }
        }.getOrElse { Result(false, it.message ?: "PJLink не ответил") }
        callback(result)
    }

    fun power(host: String, on: Boolean, password: CharArray?, callback: (Result) -> Unit) =
        command(host, "%1POWR ${if (on) 1 else 0}\r", password, if (on) "Проектор включается" else "Проектор выключается", callback)

    fun volume(host: String, up: Boolean, password: CharArray?, callback: (Result) -> Unit) =
        command(host, "%2SVOL ${if (up) 1 else 0}\r", password, if (up) "Громкость +1" else "Громкость −1", callback)

    fun mute(host: String, mute: Boolean, password: CharArray?, callback: (Result) -> Unit) =
        command(host, "%1AVMT ${if (mute) 21 else 20}\r", password, if (mute) "Звук выключен" else "Звук включён", callback)

    fun close() = executor.shutdownNow()

    private fun command(host: String, pjCommand: String, password: CharArray?, success: String, callback: (Result) -> Unit) = executor.execute {
        val result = runCatching {
            val response = execute(host, pjCommand, password)
            when {
                response.startsWith("PJLINK ERRA") -> error("Неверный пароль PJLink")
                "=OK" in response -> Result(true, success)
                "=ERR1" in response -> error("Команда не поддерживается проектором")
                "=ERR2" in response -> error("Недопустимый параметр PJLink")
                "=ERR3" in response -> error("Команда сейчас недоступна")
                "=ERR4" in response -> error("Проектор сообщил об ошибке")
                else -> error(response.ifBlank { "Проектор не подтвердил команду" })
            }
        }.getOrElse { Result(false, it.message ?: "Ошибка PJLink") }
        password?.fill('\u0000')
        callback(result)
    }

    private fun execute(host: String, command: String, password: CharArray?): String {
        require(LocalEndpointPolicy.isPrivateIpv4(host)) { "PJLink: разрешены только private LAN IPv4" }
        val socket = connect(host)
        socket.use {
            val input = it.getInputStream()
            val greeting = BoundedIo.readAsciiLine(input, MAX_LINE).orEmpty()
            when {
                greeting.startsWith("PJLINK 0") -> return sendAndRead(it, input, command)
                greeting.startsWith("PJLINK 1 ") -> {
                    val supplied = password ?: error("Проектор требует пароль PJLink")
                    // PJLink 2.10: ask whether SHA-256 security is supported.
                    it.getOutputStream().write("PJLINK 2\r".toByteArray(Charsets.US_ASCII))
                    it.getOutputStream().flush()
                    val securityReply = runCatching { BoundedIo.readAsciiLine(input, MAX_LINE).orEmpty() }.getOrDefault("")
                    if (securityReply.startsWith("PJLINK 2 ") && securityReply.substringAfter("PJLINK 2 ").trim().length == 32) {
                        val projectorRandomHex = securityReply.substringAfter("PJLINK 2 ").trim()
                        val projectorRandom = hexToBytes(projectorRandomHex)
                        val controllerRandom = ByteArray(16).also(random::nextBytes)
                        val xor = ByteArray(16) { i -> (projectorRandom[i].toInt() xor controllerRandom[i].toInt()).toByte() }
                        val xorHex = xor.toHex()
                        val hashHex = digestWithPassword("SHA-256", xorHex, supplied)
                        val payload = controllerRandom.toHex() + hashHex + command
                        return sendAndRead(it, input, payload)
                    }
                }
                else -> error("Это не PJLink-устройство")
            }
        }
        // Older PJLink devices: reconnect and use the legacy MD5 challenge flow.
        return executeLegacy(host, command, password)
    }

    private fun executeLegacy(host: String, command: String, password: CharArray?): String {
        connect(host).use { socket ->
            val input = socket.getInputStream()
            val greeting = BoundedIo.readAsciiLine(input, MAX_LINE).orEmpty()
            return when {
                greeting.startsWith("PJLINK 0") -> sendAndRead(socket, input, command)
                greeting.startsWith("PJLINK 1 ") -> {
                    val challenge = greeting.substringAfter("PJLINK 1 ").trim()
                    val supplied = password ?: error("Проектор требует пароль PJLink")
                    val prefix = digestWithPassword("MD5", challenge, supplied)
                    sendAndRead(socket, input, prefix + command)
                }
                else -> error("PJLink не отвечает")
            }
        }
    }

    private fun connect(host: String): Socket {
        require(LocalEndpointPolicy.isPrivateIpv4(host)) { "PJLink: разрешены только private LAN IPv4" }
        return Socket().apply {
        connect(InetSocketAddress(host, 4352), 1800)
        soTimeout = 2300
        }
    }

    private fun sendAndRead(socket: Socket, input: java.io.InputStream, payload: String): String {
        socket.getOutputStream().write(payload.toByteArray(Charsets.US_ASCII))
        socket.getOutputStream().flush()
        return BoundedIo.readAsciiLine(input, MAX_LINE).orEmpty()
    }

    private fun digestWithPassword(algorithm: String, prefix: String, password: CharArray): String {
        val digest = MessageDigest.getInstance(algorithm)
        digest.update(prefix.toByteArray(Charsets.US_ASCII))
        val passBytes = ByteArray(password.size) { i -> password[i].code.toByte() }
        try {
            digest.update(passBytes)
            return digest.digest().toHex()
        } finally {
            passBytes.fill(0)
        }
    }

    private fun hexToBytes(value: String): ByteArray {
        require(value.length % 2 == 0) { "Некорректный PJLink challenge" }
        return ByteArray(value.length / 2) { i -> value.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val MAX_LINE = 8 * 1024
    }
}
