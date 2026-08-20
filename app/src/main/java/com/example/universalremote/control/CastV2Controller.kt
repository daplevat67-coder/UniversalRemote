package com.example.universalremote.control

import android.content.Context
import com.example.universalremote.network.LocalEndpointPolicy
import com.example.universalremote.security.SecureStore
import com.example.universalremote.security.TofuTls
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket

/** Minimal local Google Cast v2 controller. It controls an already-running Cast receiver; it does not launch arbitrary media. */
class CastV2Controller(context: Context) {
    data class Result(val ok: Boolean, val message: String)

    private val executor = Executors.newFixedThreadPool(3)
    private val requestIds = AtomicInteger(1)
    private val secrets = SecureStore(context, "cast_tls_pins")
    private val tofu = TofuTls(secrets, "cast")

    fun probe(host: String, callback: (Result) -> Unit) = run(host, callback) { session ->
        session.receiverStatus()
        Result(true, "Google Cast v2 доступен")
    }

    fun adjustVolume(host: String, delta: Double, callback: (Result) -> Unit) = run(host, callback) { session ->
        val status = session.receiverStatus()
        val current = status.optJSONObject("status")?.optJSONObject("volume")?.optDouble("level", 0.5) ?: 0.5
        val level = (current + delta).coerceIn(0.0, 1.0)
        session.receiverCommand(JSONObject().put("type", "SET_VOLUME").put("requestId", requestIds.getAndIncrement()).put("volume", JSONObject().put("level", level)))
        Result(true, "Cast: громкость ${(level * 100).toInt()}%")
    }

    fun mute(host: String, muted: Boolean, callback: (Result) -> Unit) = run(host, callback) { session ->
        session.receiverCommand(JSONObject().put("type", "SET_VOLUME").put("requestId", requestIds.getAndIncrement()).put("volume", JSONObject().put("muted", muted)))
        Result(true, if (muted) "Cast: mute" else "Cast: звук включён")
    }

    fun media(host: String, action: String, callback: (Result) -> Unit) = run(host, callback) { session ->
        val receiver = session.receiverStatus()
        val apps = receiver.optJSONObject("status")?.optJSONArray("applications")
        val app = apps?.optJSONObject(0) ?: return@run Result(false, "Cast: сейчас нет активного приложения")
        val transportId = app.optString("transportId")
        if (transportId.isBlank()) return@run Result(false, "Cast: transportId не найден")
        session.connectDestination(transportId)
        val mediaStatus = session.mediaStatus(transportId)
        val statuses = mediaStatus.optJSONArray("status")
        val mediaSessionId = statuses?.optJSONObject(0)?.optInt("mediaSessionId", -1) ?: -1
        if (mediaSessionId < 0) return@run Result(false, "Cast: активная медиасессия не найдена")
        val command = JSONObject()
            .put("type", action.uppercase())
            .put("requestId", requestIds.getAndIncrement())
            .put("mediaSessionId", mediaSessionId)
        session.send(transportId, NS_MEDIA, command)
        Result(true, "Cast: ${action.lowercase()}")
    }

    private fun run(host: String, callback: (Result) -> Unit, block: (Session) -> Result) {
        executor.execute {
            val result = runCatching {
                require(LocalEndpointPolicy.isPrivateIpv4(host)) { "разрешены только private LAN IPv4" }
                open(host).use { socket ->
                    val value = block(Session(socket))
                    if (value.ok) tofu.pin(host, socket)
                    value
                }
            }.getOrElse { Result(false, "Google Cast недоступен: ${it.message ?: it.javaClass.simpleName}") }
            callback(result)
        }
    }

    private fun open(host: String): SSLSocket {
        val raw = java.net.Socket()
        raw.connect(InetSocketAddress(host, 8009), 2200)
        raw.soTimeout = 2800
        val socket = tofu.sslContext(host).socketFactory.createSocket(raw, host, 8009, true) as SSLSocket
        socket.soTimeout = 2800
        socket.startHandshake()
        return socket
    }

    private inner class Session(private val socket: SSLSocket) {
        private val input = DataInputStream(socket.inputStream)
        private val output = DataOutputStream(socket.outputStream)

        init { connectDestination("receiver-0") }

        fun connectDestination(destination: String) {
            send(destination, NS_CONNECTION, JSONObject().put("type", "CONNECT").put("origin", JSONObject()))
        }

        fun receiverStatus(): JSONObject {
            val id = requestIds.getAndIncrement()
            send("receiver-0", NS_RECEIVER, JSONObject().put("type", "GET_STATUS").put("requestId", id))
            return readJson(NS_RECEIVER) { it.optString("type") == "RECEIVER_STATUS" }
        }

        fun receiverCommand(command: JSONObject): JSONObject {
            send("receiver-0", NS_RECEIVER, command)
            return readJson(NS_RECEIVER) { it.optString("type").contains("STATUS") || it.optInt("requestId", -1) == command.optInt("requestId", -2) }
        }

        fun mediaStatus(destination: String): JSONObject {
            val id = requestIds.getAndIncrement()
            send(destination, NS_MEDIA, JSONObject().put("type", "GET_STATUS").put("requestId", id))
            return readJson(NS_MEDIA) { it.optString("type") == "MEDIA_STATUS" }
        }

        fun send(destination: String, namespace: String, payload: JSONObject) {
            val bytes = encodeCastMessage("sender-0", destination, namespace, payload.toString())
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
        }

        private fun readJson(namespace: String, accept: (JSONObject) -> Boolean): JSONObject {
            repeat(8) {
                val size = input.readInt()
                require(size in 1..1_048_576) { "Cast frame size $size" }
                val bytes = ByteArray(size)
                input.readFully(bytes)
                val decoded = decodeCastMessage(bytes)
                if (decoded.payload.isBlank()) return@repeat
                val json = runCatching { JSONObject(decoded.payload) }.getOrNull() ?: return@repeat
                if (decoded.namespace == NS_HEARTBEAT && json.optString("type") == "PING") {
                    send("receiver-0", NS_HEARTBEAT, JSONObject().put("type", "PONG"))
                    return@repeat
                }
                if (decoded.namespace != namespace) return@repeat
                if (accept(json)) return json
            }
            error("Cast response timeout")
        }
    }

    private data class Decoded(val namespace: String, val payload: String)

    private fun encodeCastMessage(source: String, destination: String, namespace: String, payload: String): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, 0) // CASTV2_1_0
        writeStringField(out, 2, source)
        writeStringField(out, 3, destination)
        writeStringField(out, 4, namespace)
        writeVarintField(out, 5, 0) // STRING
        writeStringField(out, 6, payload)
        return out.toByteArray()
    }

    private fun decodeCastMessage(bytes: ByteArray): Decoded {
        var pos = 0
        var namespace = ""
        var payload = ""
        while (pos < bytes.size) {
            val key = readVarint(bytes, pos); pos = key.second
            val field = (key.first ushr 3).toInt()
            val wire = (key.first and 7).toInt()
            when (wire) {
                0 -> { val v = readVarint(bytes, pos); pos = v.second }
                2 -> {
                    val len = readVarint(bytes, pos); pos = len.second
                    val n = len.first.toInt()
                    if (n < 0 || pos + n > bytes.size) error("Bad Cast protobuf")
                    val text = String(bytes, pos, n, Charsets.UTF_8)
                    if (field == 4) namespace = text
                    if (field == 6) payload = text
                    pos += n
                }
                else -> error("Unsupported Cast protobuf wire type $wire")
            }
        }
        return Decoded(namespace, payload)
    }

    private fun writeVarintField(out: ByteArrayOutputStream, field: Int, value: Long) {
        writeVarint(out, ((field shl 3) or 0).toLong()); writeVarint(out, value)
    }
    private fun writeStringField(out: ByteArrayOutputStream, field: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarint(out, ((field shl 3) or 2).toLong()); writeVarint(out, bytes.size.toLong()); out.write(bytes)
    }
    private fun writeVarint(out: ByteArrayOutputStream, value0: Long) {
        var value = value0
        while (true) {
            if (value and -128L == 0L) { out.write(value.toInt()); return }
            out.write(((value and 127L) or 128L).toInt()); value = value ushr 7
        }
    }
    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
        var value = 0L; var shift = 0; var pos = start
        while (pos < bytes.size && shift < 64) {
            val b = bytes[pos++].toInt() and 0xff
            value = value or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return value to pos
            shift += 7
        }
        error("Bad varint")
    }

    fun close() = executor.shutdownNow()

    companion object {
        private const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        private const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
    }
}
