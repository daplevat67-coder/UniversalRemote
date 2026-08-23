package com.example.universalremote.control

import android.content.Context
import android.util.Base64
import com.example.universalremote.network.BoundedIo
import com.example.universalremote.network.LocalEndpointPolicy
import com.example.universalremote.security.SecureStore
import com.example.universalremote.security.TofuTls
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Samsung Tizen LAN remote. Pairing is approved on the TV; token + TLS pin are stored encrypted. */
class SamsungTvController(context: Context) {
    data class Result(val ok: Boolean, val message: String)

    private val app = context.applicationContext
    private val executor = Executors.newFixedThreadPool(3)
    private val secrets = SecureStore(app, "samsung_tv_tokens")
    private val tofu = TofuTls(secrets, "samsung")
    private val clients = ConcurrentHashMap<String, OkHttpClient>()

    fun isPaired(host: String): Boolean = tofu.isPinned(host) && !secrets.getString(tokenKey(host)).isNullOrBlank()
    fun isTlsTrusted(host: String): Boolean = tofu.isPinned(host)
    fun inspectTls(host: String, callback: (Result, String?) -> Unit) = executor.execute {
        val result = runCatching {
            LocalEndpointPolicy.requireCurrentWifiSubnet(app, host)
            val fp = tofu.inspectFingerprint(LocalEndpointPolicy.currentNetwork(app), host, 8002)
            Result(true, "Samsung TLS fingerprint получен через текущую LAN") to fp
        }.getOrElse { Result(false, "Samsung TLS: ${it.message ?: it.javaClass.simpleName}") to null }
        callback(result.first, result.second)
    }
    fun approveTls(host: String, fingerprint: String): Result = runCatching {
        LocalEndpointPolicy.requireCurrentWifiSubnet(app, host)
        tofu.approve(host, fingerprint); Result(true, "Samsung TLS-сертификат закреплён")
    }.getOrElse { Result(false, "Samsung TLS: ${it.message}") }

    fun forget(host: String) {
        secrets.remove(tokenKey(host))
        tofu.forget(host)
        val prefix = "$host@"
        clients.keys.filter { it.startsWith(prefix) }.forEach { key -> clients.remove(key)?.closeResources() }
    }

    fun key(host: String, key: String, callback: (Result) -> Unit) {
        executor.execute { connectAndSend(host, key, callback, allowTokenReset = true) }
    }

    /** Passive API identification. HTTP:8001 is allowed here only because no credential is sent. */
    fun probe(host: String, callback: (Result) -> Unit) {
        executor.execute {
            if (!LocalEndpointPolicy.isInCurrentWifiSubnet(app, host)) return@execute callback(Result(false, "Samsung: адрес вне текущей Wi-Fi/LAN подсети"))
            val urls = listOf("https://$host:8002/api/v2/", "http://$host:8001/api/v2/")
            var last = "Samsung Tizen API не найден"
            for (url in urls) {
                val result = runCatching {
                    client(host).newCall(Request.Builder().url(url).build()).execute().use { r ->
                        val body = BoundedIo.readUtf8(r.body?.byteStream())
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
        if (!LocalEndpointPolicy.isInCurrentWifiSubnet(app, host)) return callback(Result(false, "Samsung: адрес вне текущей Wi-Fi/LAN подсети"))
        val name = Base64.encodeToString("Universal Remote".toByteArray(), Base64.NO_WRAP)
        if (!tofu.isPinned(host) && secrets.getString(tokenKey(host)) != null) {
            secrets.remove(tokenKey(host))
        }
        val token = secrets.getString(tokenKey(host))
        val url = "wss://$host:8002/api/v2/channels/samsung.remote.control?name=${enc(name)}" + (token?.let { "&token=${enc(it)}" } ?: "")
        val finished = AtomicBoolean(false)
        var openedResponse: Response? = null
        client(host).newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { openedResponse = response }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 256 * 1024) {
                    webSocket.close(1009, "message too large")
                    if (finished.compareAndSet(false, true)) callback(Result(false, "Samsung: слишком большой ответ устройства"))
                    return
                }
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (json.optString("event")) {
                    "ms.channel.connect" -> {
                        openedResponse?.let { tofu.pin(host, it) }
                        json.optJSONObject("data")?.optString("token")?.takeIf { it.isNotBlank() }?.let { secrets.putString(tokenKey(host), it) }
                        val payload = JSONObject()
                            .put("method", "ms.remote.control")
                            .put("params", JSONObject()
                                .put("Cmd", "Click")
                                .put("DataOfCmd", key)
                                .put("Option", "false")
                                .put("TypeOfRemote", "SendRemoteKey"))
                        val sent = webSocket.send(payload.toString())
                        webSocket.close(1000, "done")
                        if (finished.compareAndSet(false, true)) callback(Result(sent, if (sent) "Samsung: команда $key отправлена" else "Samsung: WebSocket не принял команду"))
                    }
                    "ms.channel.unauthorized" -> {
                        webSocket.close(1000, "unauthorized")
                        if (!finished.compareAndSet(false, true)) return
                        if (token != null && allowTokenReset) {
                            secrets.remove(tokenKey(host))
                            connectAndSend(host, key, callback, allowTokenReset = false)
                        } else callback(Result(false, "Samsung: на TV выберите «Разрешить» для Universal Remote."))
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
                callback(Result(false, "Samsung WSS: ${t.message ?: t.javaClass.simpleName}. Автоматический fallback на ws://:8001 отключён."))
            }
        })
    }

    private fun client(host: String): OkHttpClient {
        val network = LocalEndpointPolicy.currentNetwork(app)
        val cacheKey = "$host@${network?.networkHandle ?: 0L}"
        return clients.getOrPut(cacheKey) {
            val trust = tofu.trustManager(host)
            val ssl = tofu.sslContext(host)
            OkHttpClient.Builder()
                .apply { if (network != null) socketFactory(network.socketFactory) }
                .sslSocketFactory(ssl.socketFactory, trust)
                .hostnameVerifier { _, session -> tofu.verifyPinnedSession(host, session) }
                .connectTimeout(2200, TimeUnit.MILLISECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }
    }

    private fun OkHttpClient.closeResources() {
        dispatcher.executorService.shutdownNow()
        connectionPool.evictAll()
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
    private fun tokenKey(host: String) = "token_$host"

    fun close() {
        executor.shutdownNow()
        clients.values.forEach { it.closeResources() }
        clients.clear()
    }
}
