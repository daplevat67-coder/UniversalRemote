package com.example.universalremote.control

import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class YeelightController {
    data class Result(val ok: Boolean, val message: String)

    private val executor = Executors.newCachedThreadPool()
    private val ids = AtomicInteger(1)

    fun probe(host: String, callback: (Result) -> Unit) = send(host, "get_prop", JSONArray().put("power").put("bright").put("rgb"), callback)

    fun power(host: String, on: Boolean, callback: (Result) -> Unit) =
        send(host, "set_power", JSONArray().put(if (on) "on" else "off").put("smooth").put(300), callback)

    fun brightness(host: String, value: Int, callback: (Result) -> Unit) =
        send(host, "set_bright", JSONArray().put(value.coerceIn(1, 100)).put("smooth").put(300), callback)

    fun color(host: String, r: Int, g: Int, b: Int, callback: (Result) -> Unit) {
        val rgb = ((r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255))
        send(host, "set_rgb", JSONArray().put(rgb).put("smooth").put(300), callback)
    }

    private fun send(host: String, method: String, params: JSONArray, callback: (Result) -> Unit) {
        executor.execute {
            val result = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, 55443), 1800)
                    socket.soTimeout = 1800
                    val request = JSONObject()
                        .put("id", ids.getAndIncrement())
                        .put("method", method)
                        .put("params", params)
                        .toString() + "\r\n"
                    socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()
                    val line = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine().orEmpty()
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
