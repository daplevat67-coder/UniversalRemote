package com.example.universalremote.control

import android.content.Context
import com.example.universalremote.network.BoundedIo
import com.example.universalremote.network.LocalEndpointPolicy
import com.example.universalremote.security.SecureStore
import com.example.universalremote.security.TofuTls
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Philips Hue local API. Application keys and TOFU certificate pins are encrypted with Android Keystore. */
class HueController(context: Context) {
    data class Result(val ok: Boolean, val message: String)
    data class Light(val id: String, val name: String)

    private val secrets = SecureStore(context, "hue_bridges")
    private val tofu = TofuTls(secrets, "hue")
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val clients = ConcurrentHashMap<String, OkHttpClient>()

    fun isPaired(host: String): Boolean = tofu.isPinned(host) && !secrets.getString("key_$host").isNullOrBlank()
    fun forget(host: String) {
        secrets.remove("key_$host", "scheme_$host", "fp_$host")
        tofu.forget(host)
        clients.remove(host)?.closeResources()
    }

    /** Passive bridge identification. HTTP fallback never carries an application key. */
    fun probe(host: String, callback: (Result) -> Unit) {
        if (!LocalEndpointPolicy.isPrivateIpv4(host)) return callback(Result(false, "Hue: разрешены только private LAN IPv4"))
        val urls = listOf("https://$host/api/config", "http://$host/api/config")
        fun attempt(index: Int) {
            if (index >= urls.size) return callback(Result(false, "Philips Hue Bridge API не найден"))
            client(host).newCall(Request.Builder().url(urls[index]).get().build()).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = attempt(index + 1)
                override fun onResponse(call: okhttp3.Call, response: Response) {
                    response.use {
                        val body = runCatching { BoundedIo.readUtf8(it.body?.byteStream()) }.getOrElse { err ->
                            return callback(Result(false, "Hue: ${err.message}"))
                        }
                        val json = runCatching { JSONObject(body) }.getOrNull()
                        val name = json?.optString("name").orEmpty()
                        val bridgeId = json?.optString("bridgeid").orEmpty()
                        if (it.isSuccessful && (bridgeId.isNotBlank() || body.contains("philips hue", true))) {
                            callback(Result(true, if (name.isBlank()) "Philips Hue Bridge" else "$name • Hue Bridge"))
                        } else attempt(index + 1)
                    }
                }
            })
        }
        attempt(0)
    }

    fun pair(host: String, callback: (Result) -> Unit) {
        if (!LocalEndpointPolicy.isPrivateIpv4(host)) return callback(Result(false, "Hue: адрес вне private LAN"))
        val body = JSONObject().put("devicetype", "universal_remote#android").put("generateclientkey", true).toString().toRequestBody(jsonType)
        val request = Request.Builder().url("https://$host/api").post(body).build()
        client(host).newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                callback(Result(false, "Hue HTTPS недоступен: ${e.message ?: e.javaClass.simpleName}. Небезопасный fallback на HTTP отключён."))
            }
            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    val text = runCatching { BoundedIo.readUtf8(it.body?.byteStream()) }.getOrElse { err ->
                        return callback(Result(false, "Hue: ${err.message}"))
                    }
                    val array = runCatching { JSONArray(text) }.getOrNull()
                    val item = array?.optJSONObject(0)
                    val success = item?.optJSONObject("success")
                    val username = success?.optString("username").orEmpty()
                    if (username.isNotBlank()) {
                        tofu.pin(host, it)
                        secrets.putString("key_$host", username)
                        callback(Result(true, "Hue Bridge авторизован по HTTPS. Application key сохранён через Android Keystore."))
                    } else {
                        val description = item?.optJSONObject("error")?.optString("description").orEmpty()
                        callback(Result(false, if (description.isBlank()) "Hue: авторизация не получена" else "Hue: $description"))
                    }
                }
            }
        })
    }

    fun listLights(host: String, callback: (Result, List<Light>) -> Unit) {
        api(host, "GET", "/clip/v2/resource/light", null) { result, body ->
            if (!result.ok || body == null) return@api callback(result, emptyList())
            val lights = runCatching {
                val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
                buildList {
                    for (i in 0 until data.length()) {
                        val item = data.optJSONObject(i) ?: continue
                        val id = item.optString("id")
                        if (id.isBlank()) continue
                        val name = item.optJSONObject("metadata")?.optString("name")?.takeIf { it.isNotBlank() } ?: "Hue light ${i + 1}"
                        add(Light(id, name))
                    }
                }
            }.getOrElse { emptyList() }
            callback(Result(true, "Hue: найдено ${lights.size} ламп"), lights)
        }
    }

    fun power(host: String, lightId: String, on: Boolean, callback: (Result) -> Unit) =
        control(host, lightId, JSONObject().put("on", JSONObject().put("on", on)), callback)

    fun brightness(host: String, lightId: String, percent: Int, callback: (Result) -> Unit) =
        control(host, lightId, JSONObject().put("dimming", JSONObject().put("brightness", percent.coerceIn(1, 100))), callback)

    fun color(host: String, lightId: String, x: Double, y: Double, callback: (Result) -> Unit) =
        control(host, lightId, JSONObject().put("color", JSONObject().put("xy", JSONObject().put("x", x).put("y", y))), callback)

    private fun control(host: String, id: String, body: JSONObject, callback: (Result) -> Unit) {
        api(host, "PUT", "/clip/v2/resource/light/$id", body) { result, _ -> callback(result) }
    }

    private fun api(host: String, method: String, path: String, body: JSONObject?, callback: (Result, String?) -> Unit) {
        if (!LocalEndpointPolicy.isPrivateIpv4(host)) return callback(Result(false, "Hue: адрес вне private LAN"), null)
        if (!tofu.isPinned(host) && secrets.getString("key_$host") != null) {
            // Legacy v0.7.0 key has no certificate binding; require the physical Hue link button once more.
            secrets.remove("key_$host")
        }
        val appKey = secrets.getString("key_$host") ?: return callback(Result(false, "Сначала нажмите кнопку на Hue Bridge и авторизуйте приложение"), null)
        val builder = Request.Builder().url("https://$host$path").header("hue-application-key", appKey)
        when (method) {
            "GET" -> builder.get()
            "PUT" -> builder.put((body ?: JSONObject()).toString().toRequestBody(jsonType))
            else -> return callback(Result(false, "Hue: неподдерживаемый метод"), null)
        }
        client(host).newCall(builder.build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = callback(Result(false, "Hue HTTPS недоступен: ${e.message ?: e.javaClass.simpleName}"), null)
            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    val text = runCatching { BoundedIo.readUtf8(it.body?.byteStream()) }.getOrElse { err ->
                        return callback(Result(false, "Hue: ${err.message}"), null)
                    }
                    if (!it.isSuccessful) callback(Result(false, "Hue HTTP ${it.code}: ${text.take(120)}"), text)
                    else callback(Result(true, "Hue: команда выполнена"), text)
                }
            }
        })
    }

    private fun client(host: String): OkHttpClient = clients.getOrPut(host) {
        val trust = tofu.trustManager(host)
        val ssl = tofu.sslContext(host)
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private fun OkHttpClient.closeResources() {
        dispatcher.executorService.shutdownNow()
        connectionPool.evictAll()
    }

    fun close() {
        clients.values.forEach { it.closeResources() }
        clients.clear()
    }
}
