package com.example.universalremote.control

import android.content.Context
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Samsung Tizen LAN remote. Authorization is approved on the TV and the issued token is persisted locally. */
class SamsungTvController(context: Context) {
    data class Result(val ok: Boolean, val message: String)

    private val executor = Executors.newCachedThreadPool()
    private val prefs = context.getSharedPreferences("samsung_tv_tokens", Context.MODE_PRIVATE)
    private val client: OkHttpClient = buildClient()

    fun isPaired(host: String): Boolean = !prefs.getString(tokenKey(host), null).isNullOrBlank()

    fun forget(host: String) {
        prefs.edit().remove(tokenKey(host)).apply()
    }

    fun key(host: String, key: String, callback: (Result) -> Unit) {
        executor.execute { connectAndSend(host, key, callback, allowTokenReset = true) }
    }

    /** Passive HTTP probe; does not request remote-control authorization. */
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

    private fun connectAndSend(host: String, key: String, callback: (Result) -> Unit, allowTokenReset: Boolean) {
        val name = Base64.encodeToString("Universal Remote".toByteArray(), Base64.NO_WRAP)
        val token = prefs.getString(tokenKey(host), null)
        val urls = buildList {
            add("wss://$host:8002/api/v2/channels/samsung.remote.control?name=${enc(name)}" + (token?.let { "&token=${enc(it)}" } ?: ""))
            add("ws://$host:8001/api/v2/channels/samsung.remote.control?name=${enc(name)}" + (token?.let { "&token=${enc(it)}" } ?: ""))
        }
        tryUrl(host, key, urls, 0, callback, allowTokenReset, token != null)
    }

    private fun tryUrl(
        host: String,
        key: String,
        urls: List<String>,
        index: Int,
        callback: (Result) -> Unit,
        allowTokenReset: Boolean,
        hadToken: Boolean
    ) {
        if (index >= urls.size) {
            return callback(Result(false, "Samsung TV не ответил на remote API 8002/8001. Проверьте, что телефон и TV в одной LAN и управление с мобильных устройств разрешено."))
        }

        val finished = AtomicBoolean(false)
        val request = Request.Builder().url(urls[index]).build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (json.optString("event")) {
                    "ms.channel.connect" -> {
                        json.optJSONObject("data")?.optString("token")?.takeIf { it.isNotBlank() }?.let {
                            prefs.edit().putString(tokenKey(host), it).apply()
                        }
                        val payload = JSONObject()
                            .put("method", "ms.remote.control")
                            .put("params", JSONObject()
                                .put("Cmd", "Click")
                                .put("DataOfCmd", key)
                                .put("Option", "false")
                                .put("TypeOfRemote", "SendRemoteKey"))
                        val sent = webSocket.send(payload.toString())
                        webSocket.close(1000, "done")
                        if (finished.compareAndSet(false, true)) {
                            callback(Result(sent, if (sent) "Samsung: команда $key отправлена" else "Samsung: WebSocket не принял команду"))
                        }
                    }

                    "ms.channel.unauthorized" -> {
                        webSocket.close(1000, "unauthorized")
                        if (!finished.compareAndSet(false, true)) return
                        if (hadToken && allowTokenReset) {
                            // A TV reset/update can invalidate a previously issued token. Retry once without it so the TV can show Allow again.
                            forget(host)
                            connectAndSend(host, key, callback, allowTokenReset = false)
                        } else {
                            callback(Result(false, "Samsung: на телевизоре выберите «Разрешить» для Universal Remote. Если запрос не появляется, удалите Universal Remote из списка разрешённых устройств TV и попробуйте снова."))
                        }
                    }

                    "ms.error" -> {
                        webSocket.close(1000, "error")
                        if (finished.compareAndSet(false, true)) {
                            val msg = json.optJSONObject("data")?.optString("message").orEmpty().ifBlank { "TV отклонил команду" }
                            callback(Result(false, "Samsung: $msg"))
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!finished.compareAndSet(false, true)) return
                tryUrl(host, key, urls, index + 1, callback, allowTokenReset, hadToken)
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
            .connectTimeout(2200, TimeUnit.MILLISECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
    private fun tokenKey(host: String) = "token_$host"

    fun close() {
        executor.shutdownNow()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
    }
}
