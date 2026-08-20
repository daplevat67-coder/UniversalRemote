package com.example.universalremote.control

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Samsung Tizen LAN remote. Authorization is approved on the TV; tokens are kept only in memory. */
class SamsungTvController {
    data class Result(val ok: Boolean, val message: String)
    private val executor = Executors.newCachedThreadPool()
    private val tokens = ConcurrentHashMap<String, String>()
    private val client: OkHttpClient = buildClient()

    fun key(host: String, key: String, callback: (Result) -> Unit) {
        executor.execute { connectAndSend(host, key, callback) }
    }

    fun probe(host: String, callback: (Result) -> Unit) {
        executor.execute {
            val urls = listOf("http://$host:8001/api/v2/", "https://$host:8002/api/v2/")
            var last = "Samsung Tizen API не найден"
            for (url in urls) {
                val result = runCatching {
                    val response = client.newCall(Request.Builder().url(url).build()).execute()
                    response.use { r ->
                        val body = r.body?.string().orEmpty()
                        if (!r.isSuccessful) error("HTTP ${r.code}")
                        val json = JSONObject(body)
                        val device = json.optJSONObject("device")
                        val text = body.lowercase()
                        if (device == null && "samsung" !in text && "tizen" !in text) error("Ответ не похож на Samsung TV")
                        val name = device?.optString("name")?.takeIf { it.isNotBlank() } ?: "Samsung TV"
                        Result(true, "$name • Tizen LAN API")
                    }
                }
                if (result.isSuccess) return@execute callback(result.getOrThrow())
                last = result.exceptionOrNull()?.message ?: last
            }
            callback(Result(false, last))
        }
    }

    private fun connectAndSend(host: String, key: String, callback: (Result) -> Unit) {
        val name = Base64.encodeToString("Universal Remote".toByteArray(), Base64.NO_WRAP)
        val token = tokens[host]
        val urls = buildList {
            add("wss://$host:8002/api/v2/channels/samsung.remote.control?name=${enc(name)}" + (token?.let { "&token=${enc(it)}" } ?: ""))
            add("ws://$host:8001/api/v2/channels/samsung.remote.control?name=${enc(name)}" + (token?.let { "&token=${enc(it)}" } ?: ""))
        }
        tryUrl(host, key, urls, 0, callback)
    }

    private fun tryUrl(host: String, key: String, urls: List<String>, index: Int, callback: (Result) -> Unit) {
        if (index >= urls.size) return callback(Result(false, "Samsung TV не ответил на 8002/8001"))
        val request = Request.Builder().url(urls[index]).build()
        var delivered = false
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (json.optString("event")) {
                    "ms.channel.connect" -> {
                        json.optJSONObject("data")?.optString("token")?.takeIf { it.isNotBlank() }?.let { tokens[host] = it }
                        val payload = JSONObject()
                            .put("method", "ms.remote.control")
                            .put("params", JSONObject()
                                .put("Cmd", "Click")
                                .put("DataOfCmd", key)
                                .put("Option", "false")
                                .put("TypeOfRemote", "SendRemoteKey"))
                        delivered = webSocket.send(payload.toString())
                        webSocket.close(1000, "done")
                        callback(Result(delivered, if (delivered) "Samsung: $key" else "Не удалось отправить команду"))
                    }
                    "ms.channel.unauthorized" -> {
                        webSocket.close(1000, "unauthorized")
                        callback(Result(false, "На телевизоре выберите «Разрешить» для Universal Remote"))
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!delivered) tryUrl(host, key, urls, index + 1, callback)
            }
        })
    }

    private fun buildClient(): OkHttpClient {
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(1800, TimeUnit.MILLISECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
    fun close() { executor.shutdownNow(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
}
