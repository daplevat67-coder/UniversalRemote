package com.example.universalremote.control

import com.example.universalremote.network.BoundedIo
import com.example.universalremote.network.LocalEndpointPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class YeelightController {
    data class Result(val ok: Boolean, val message: String)

    private val executor = Executors.newFixedThreadPool(3)
    private val ids = AtomicInteger(1)

    fun probe(host: String, port: Int = 55443, callback: (Result) -> Unit) = send(host, port, "get_prop", JSONArray().put("power").put("bright").put("rgb"), callback)

    fun power(host: String, on: Boolean, port: Int = 55443, callback: (Result) -> Unit) =
        send(host, port, "set_power", JSONArray().put(if (on) "on" else "off").put("smooth").put(300), callback)

    fun brightness(host: String, value: Int, port: Int = 55443, callback: (Result) -> Unit) =
        send(host, port, "set_bright", JSONArray().put(value.coerceIn(1, 100)).put("smooth").put(300), callback)

    fun color(host: String, r: Int, g: Int, b: Int, port: Int = 55443, callback: (Result) -> Unit) {
        val rgb = ((r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255))
        send(host, port, "set_rgb", JSONArray().put(rgb).put("smooth").put(300), callback)
    }

    private fun send(host: String, port: Int, method: String, params: JSONArray, callback: (Result) -> Unit) {
        executor.execute {
            val result = runCatching {
                require(LocalEndpointPolicy.isPrivateIpv4(host)) { "Yeelight: разрешены только private LAN IPv4" }
                require(port in 1..65535) { "Yeelight: некорректный порт" }
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 1800)
                    socket.soTimeout = 1800
                    val request = JSONObject()
                        .put("id", ids.getAndIncrement())
                        .put("method", method)
                        .put("params", params)
                        .toString() + "\r\n"
                    socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()
                    val line = BoundedIo.readAsciiLine(socket.getInputStream(), 64 * 1024).orEmpty()
                    val json = JSONObject(line)
                    if (json.has("error")) {
                        val msg = json.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "Yeelight вернул ошибку" }
                        Result(false, msg)
                    } else Result(true, "Yeelight: команда выполнена")
                }
            }.getOrElse { Result(false, "Yeelight недоступен: ${it.message ?: it.javaClass.simpleName}. Проверьте LAN Control в приложении Yeelight.") }
            callback(result)
        }
    }

    fun close() = executor.shutdownNow()
}
