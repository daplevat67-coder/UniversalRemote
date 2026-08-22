package com.example.universalremote.control

import android.content.Context
import com.example.universalremote.network.LocalEndpointPolicy
import com.example.universalremote.security.SecureStore
import com.example.universalremote.security.TofuTls
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** LG webOS SSAP controller. Registration is WSS-only; client-key and TLS pin are encrypted. */
class LgWebOsController(context: Context) {
    data class Result(val ok: Boolean, val message: String)

    private val secrets = SecureStore(context, "lg_webos_keys")
    private val tofu = TofuTls(secrets, "lg")
    private val clients = ConcurrentHashMap<String, OkHttpClient>()
    private val executor = Executors.newFixedThreadPool(2)

    fun isPaired(host: String): Boolean = tofu.isPinned(host) && !secrets.getString(keyName(host)).isNullOrBlank()
    fun isTlsTrusted(host: String): Boolean = tofu.isPinned(host)
    fun inspectTls(host: String, callback: (Result, String?) -> Unit) = executor.execute {
        val pair = runCatching {
            require(LocalEndpointPolicy.isPrivateIpv4(host)) { "LG: адрес вне private LAN" }
            Result(true, "LG TLS fingerprint получен") to tofu.inspectFingerprint(host, 3001)
        }.getOrElse { Result(false, "LG TLS: ${it.message ?: it.javaClass.simpleName}") to null }
        callback(pair.first, pair.second)
    }
    fun approveTls(host: String, fingerprint: String): Result = runCatching {
        tofu.approve(host, fingerprint); Result(true, "LG TLS-сертификат закреплён")
    }.getOrElse { Result(false, "LG TLS: ${it.message}") }
    fun forget(host: String) {
        secrets.remove(keyName(host))
        tofu.forget(host)
        clients.remove(host)?.closeResources()
    }

    fun probe(host: String, callback: (Result) -> Unit) = request(host, "ssap://system/getSystemInfo", null, callback)
    fun powerOff(host: String, callback: (Result) -> Unit) = request(host, "ssap://system/turnOff", null, callback)
    fun volumeUp(host: String, callback: (Result) -> Unit) = request(host, "ssap://audio/volumeUp", null, callback)
    fun volumeDown(host: String, callback: (Result) -> Unit) = request(host, "ssap://audio/volumeDown", null, callback)
    fun mute(host: String, muted: Boolean, callback: (Result) -> Unit) = request(host, "ssap://audio/setMute", JSONObject().put("mute", muted), callback)
    fun play(host: String, callback: (Result) -> Unit) = request(host, "ssap://media.controls/play", null, callback)
    fun pause(host: String, callback: (Result) -> Unit) = request(host, "ssap://media.controls/pause", null, callback)
    fun stop(host: String, callback: (Result) -> Unit) = request(host, "ssap://media.controls/stop", null, callback)
    fun rewind(host: String, callback: (Result) -> Unit) = request(host, "ssap://media.controls/rewind", null, callback)
    fun fastForward(host: String, callback: (Result) -> Unit) = request(host, "ssap://media.controls/fastForward", null, callback)

    fun button(host: String, name: String, callback: (Result) -> Unit) {
        pointerSocket(host) { result, socketPath ->
            if (!result.ok || socketPath == null) return@pointerSocket callback(result)
            if (!LocalEndpointPolicy.samePrivateHost(host, socketPath, setOf("ws", "wss"))) {
                return@pointerSocket callback(Result(false, "LG отклонил небезопасный pointer socket: другой/публичный хост"))
            }
            val done = AtomicBoolean(false)
            client(host).newWebSocket(Request.Builder().url(socketPath).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("type:button\nname:$name\n\n")
                    webSocket.close(1000, "done")
                    if (done.compareAndSet(false, true)) callback(Result(true, "LG webOS: $name"))
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (done.compareAndSet(false, true)) callback(Result(false, "LG pointer socket: ${t.message ?: t.javaClass.simpleName}"))
                }
            })
        }
    }

    private fun pointerSocket(host: String, callback: (Result, String?) -> Unit) {
        requestJson(host, "ssap://com.webos.service.networkinput/getPointerInputSocket", null) { ok, message, payload ->
            val socketPath = payload?.optString("socketPath")?.takeIf { LocalEndpointPolicy.samePrivateHost(host, it, setOf("ws", "wss")) }
            callback(Result(ok && socketPath != null, if (socketPath != null) "LG pointer socket готов" else "$message • внешний redirect отклонён"), socketPath)
        }
    }

    private fun request(host: String, uri: String, payload: JSONObject?, callback: (Result) -> Unit) {
        requestJson(host, uri, payload) { ok, message, _ -> callback(Result(ok, message)) }
    }

    private fun requestJson(host: String, uri: String, payload: JSONObject?, callback: (Boolean, String, JSONObject?) -> Unit) {
        if (!LocalEndpointPolicy.isPrivateIpv4(host)) return callback(false, "LG: разрешены только private LAN IPv4", null)
        openRegistered(host, object : RegisteredCallback {
            override fun ready(webSocket: WebSocket) {
                val id = "cmd-${System.nanoTime()}"
                val json = JSONObject().put("id", id).put("type", "request").put("uri", uri)
                if (payload != null) json.put("payload", payload)
                webSocket.send(json.toString())
            }
            override fun message(webSocket: WebSocket, json: JSONObject) {
                if (!json.optString("id").startsWith("cmd-")) return
                val type = json.optString("type")
                val responsePayload = json.optJSONObject("payload")
                val returnValue = responsePayload?.optBoolean("returnValue", type == "response") ?: (type == "response")
                val error = json.optString("error").ifBlank { responsePayload?.optString("errorText").orEmpty() }
                callback(returnValue && error.isBlank(), if (returnValue && error.isBlank()) "LG webOS: команда выполнена" else "LG webOS: ${error.ifBlank { "команда отклонена" }}", responsePayload)
                webSocket.close(1000, "done")
            }
            override fun failed(message: String) = callback(false, message, null)
        })
    }

    private interface RegisteredCallback {
        fun ready(webSocket: WebSocket)
        fun message(webSocket: WebSocket, json: JSONObject)
        fun failed(message: String)
    }

    private fun openRegistered(host: String, callback: RegisteredCallback) {
        val done = AtomicBoolean(false)
        var ready = false
        var openedResponse: Response? = null
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openedResponse = response
                webSocket.send(registration(host).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 256 * 1024) {
                    webSocket.close(1009, "message too large")
                    if (done.compareAndSet(false, true)) callback.failed("LG: слишком большой ответ устройства")
                    return
                }
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (json.optString("type") == "registered") {
                    openedResponse?.let { tofu.pin(host, it) }
                    val clientKey = json.optJSONObject("payload")?.optString("client-key").orEmpty()
                    if (clientKey.isNotBlank()) secrets.putString(keyName(host), clientKey)
                    if (!ready) {
                        ready = true
                        callback.ready(webSocket)
                    }
                    return
                }
                if (json.optString("type") == "error") {
                    if (done.compareAndSet(false, true)) callback.failed("LG webOS: подтвердите подключение на телевизоре (${json.optString("error")})")
                    webSocket.close(1000, "error")
                    return
                }
                if (ready && !done.get()) callback.message(webSocket, json)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (done.compareAndSet(false, true)) {
                    callback.failed("LG webOS WSS: ${t.message ?: t.javaClass.simpleName}. Автоматический downgrade на ws://3000 отключён; client-key не передаётся без TLS.")
                }
            }
        }
        client(host).newWebSocket(Request.Builder().url("wss://$host:3001").build(), listener)
    }

    private fun registration(host: String): JSONObject {
        val permissions = JSONArray(listOf(
            "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "TEST_OPEN",
            "TEST_PROTECTED", "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK",
            "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV",
            "CONTROL_POWER", "READ_APP_STATUS", "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST",
            "READ_INSTALLED_APPS", "READ_LGE_SDX", "READ_NETWORK_STATE", "READ_RUNNING_APPS",
            "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST", "WRITE_SETTINGS"
        ))
        val manifest = JSONObject()
            .put("manifestVersion", 1)
            .put("appVersion", "0.9.4")
            .put("signed", JSONObject().put("created", "2026-08-20").put("appId", "com.example.universalremote").put("vendorId", "com.example.universalremote").put("localizedAppNames", JSONObject().put("", "Universal Remote")).put("localizedVendorNames", JSONObject().put("", "Universal Remote")).put("permissions", permissions).put("serial", "1"))
            .put("permissions", permissions)
        val payload = JSONObject().put("forcePairing", false).put("pairingType", "PROMPT").put("manifest", manifest)
        if (tofu.isPinned(host)) {
            secrets.getString(keyName(host))?.takeIf { it.isNotBlank() }?.let { payload.put("client-key", it) }
        } else if (secrets.getString(keyName(host)) != null) {
            // Do not replay a v0.7.0 client-key before the TV certificate has been bound to a fresh approval.
            secrets.remove(keyName(host))
        }
        return JSONObject().put("id", "register-0").put("type", "register").put("payload", payload)
    }

    private fun client(host: String): OkHttpClient = clients.getOrPut(host) {
        val trust = tofu.trustManager(host)
        val ssl = tofu.sslContext(host)
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier { _, session -> tofu.verifyPinnedSession(host, session) }
            .build()
    }

    private fun OkHttpClient.closeResources() {
        dispatcher.executorService.shutdownNow()
        connectionPool.evictAll()
    }

    private fun keyName(host: String) = "client_key_$host"
    fun close() {
        executor.shutdownNow()
        clients.values.forEach { it.closeResources() }
        clients.clear()
    }
}
