package com.example.universalremote.control

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class LgWebOsController(context: Context) {
    data class Result(val ok: Boolean, val message: String)

    private val prefs = context.getSharedPreferences("lg_webos_keys", Context.MODE_PRIVATE)
    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    private val ssl = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .sslSocketFactory(ssl.socketFactory, trustManager)
        .hostnameVerifier { _, _ -> true }
        .build()

    fun isPaired(host: String): Boolean = !prefs.getString(keyName(host), null).isNullOrBlank()
    fun forget(host: String) { prefs.edit().remove(keyName(host)).apply() }

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
            val done = AtomicBoolean(false)
            val request = Request.Builder().url(socketPath).build()
            client.newWebSocket(request, object : WebSocketListener() {
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
            val socketPath = payload?.optString("socketPath")?.takeIf { it.startsWith("ws://") || it.startsWith("wss://") }
            callback(Result(ok && socketPath != null, if (socketPath != null) "LG pointer socket готов" else message), socketPath)
        }
    }

    private fun request(host: String, uri: String, payload: JSONObject?, callback: (Result) -> Unit) {
        requestJson(host, uri, payload) { ok, message, _ -> callback(Result(ok, message)) }
    }

    private fun requestJson(host: String, uri: String, payload: JSONObject?, callback: (Boolean, String, JSONObject?) -> Unit) {
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
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(registration(host).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (json.optString("type") == "registered") {
                    val clientKey = json.optJSONObject("payload")?.optString("client-key").orEmpty()
                    if (clientKey.isNotBlank()) prefs.edit().putString(keyName(host), clientKey).apply()
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
                if (done.compareAndSet(false, true)) callback.failed("LG webOS недоступен: ${t.message ?: t.javaClass.simpleName}")
            }
        }

        // Modern webOS often prefers secure 3001; fall back to legacy 3000.
        val secureRequest = Request.Builder().url("wss://$host:3001").build()
        val secureSocket = client.newWebSocket(secureRequest, object : WebSocketListener() {
            private var opened = false
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened = true
                listener.onOpen(webSocket, response)
            }
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(webSocket, text)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!opened && !done.get()) client.newWebSocket(Request.Builder().url("ws://$host:3000").build(), listener)
                else listener.onFailure(webSocket, t, response)
            }
        })
        // Keep a reference long enough for OkHttp; the client owns the socket lifecycle.
        secureSocket.request()
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
            .put("appVersion", "0.6.0")
            .put("signed", JSONObject().put("created", "2026-08-20").put("appId", "com.example.universalremote").put("vendorId", "com.example.universalremote").put("localizedAppNames", JSONObject().put("", "Universal Remote")).put("localizedVendorNames", JSONObject().put("", "Universal Remote")).put("permissions", permissions).put("serial", "1"))
            .put("permissions", permissions)
        val payload = JSONObject().put("forcePairing", false).put("pairingType", "PROMPT").put("manifest", manifest)
        prefs.getString(keyName(host), null)?.takeIf { it.isNotBlank() }?.let { payload.put("client-key", it) }
        return JSONObject().put("id", "register-0").put("type", "register").put("payload", payload)
    }

    private fun keyName(host: String) = "client_key_$host"
    fun close() { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
}
