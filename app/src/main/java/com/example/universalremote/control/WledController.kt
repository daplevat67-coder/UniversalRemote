package com.example.universalremote.control

import com.example.universalremote.network.BoundedIo
import com.example.universalremote.network.LocalEndpointPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class WledController {
    data class Result(val ok: Boolean, val message: String)
    private val executor = Executors.newFixedThreadPool(3)

    fun probe(host: String, callback: (Result) -> Unit) = executor.execute {
        callback(runCatching {
            val conn = connection(host, "/json/info", "GET")
            if (conn.responseCode !in 200..299) error("WLED HTTP ${conn.responseCode}")
            val text = conn.inputStream.use { BoundedIo.readUtf8(it) }
            val json = JSONObject(text)
            val ver = json.optString("ver")
            val name = json.optString("name", "WLED")
            if (ver.isBlank() && !text.contains("wled", true)) error("WLED API не обнаружен")
            Result(true, "$name • WLED $ver")
        }.getOrElse { Result(false, it.message ?: "WLED не ответил") })
    }

    fun power(host: String, on: Boolean, callback: (Result) -> Unit) = state(host, JSONObject().put("on", on), callback)
    fun brightness(host: String, value: Int, callback: (Result) -> Unit) = state(host, JSONObject().put("on", true).put("bri", value.coerceIn(1, 255)), callback)
    fun color(host: String, r: Int, g: Int, b: Int, callback: (Result) -> Unit) {
        val color = JSONArray().put(JSONArray().put(r).put(g).put(b))
        val seg = JSONObject().put("col", color)
        state(host, JSONObject().put("on", true).put("seg", JSONArray().put(seg)), callback)
    }

    private fun state(host: String, json: JSONObject, callback: (Result) -> Unit) = executor.execute {
        callback(runCatching {
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            val conn = connection(host, "/json/state", "POST").apply {
                doOutput = true; setRequestProperty("Content-Type", "application/json"); setFixedLengthStreamingMode(bytes.size)
            }
            conn.outputStream.use { it.write(bytes) }
            if (conn.responseCode !in 200..299) error("WLED HTTP ${conn.responseCode}")
            Result(true, "Команда WLED отправлена")
        }.getOrElse { Result(false, it.message ?: "Ошибка WLED") })
    }

    private fun connection(host: String, path: String, method: String): HttpURLConnection {
        require(LocalEndpointPolicy.isPrivateIpv4(host)) { "WLED: разрешены только private LAN IPv4" }
        return (URL("http://$host$path").openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 1400; readTimeout = 1800; requestMethod = method
        }
    }

    fun close() = executor.shutdownNow()
}
