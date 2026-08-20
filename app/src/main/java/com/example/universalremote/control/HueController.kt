package com.example.universalremote.control

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class HueController(context: Context) {
    data class Result(val ok: Boolean, val message: String)
    data class Light(val id: String, val name: String)

    private val prefs = context.getSharedPreferences("hue_bridges", Context.MODE_PRIVATE)
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    private val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustManager), SecureRandom()) }
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .sslSocketFactory(ssl.socketFactory, trustManager)
        .hostnameVerifier { _, _ -> true }
        .build()

    fun isPaired(host: String): Boolean = !prefs.getString("key_$host", null).isNullOrBlank()
    fun forget(host: String) { prefs.edit().remove("key_$host").remove("fp_$host").remove("scheme_$host").apply() }


    /** Passive bridge identification; no link-button authorization is requested. */
    fun probe(host: String, callback: (Result) -> Unit) {
        val urls = listOf("https://$host/api/config", "http://$host/api/config")
        fun attempt(index: Int) {
            if (index >= urls.size) return callback(Result(false, "Philips Hue Bridge API не найден"))
            client.newCall(Request.Builder().url(urls[index]).get().build()).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = attempt(index + 1)
                override fun onResponse(call: okhttp3.Call, response: Response) {
                    response.use {
                        val body = it.body?.string().orEmpty()
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
        pairAttempt(host, "https") { result ->
            if (result.ok || !result.message.contains("network", true)) callback(result)
            else pairAttempt(host, "http", callback)
        }
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

    private fun pairAttempt(host: String, scheme: String, callback: (Result) -> Unit) {
        val body = JSONObject().put("devicetype", "universal_remote#android").put("generateclientkey", true).toString().toRequestBody(jsonType)
        val request = Request.Builder().url("$scheme://$host/api").post(body).build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                callback(Result(false, "Hue network error: ${e.message ?: e.javaClass.simpleName}"))
            }
            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    val array = runCatching { JSONArray(text) }.getOrNull()
                    val item = array?.optJSONObject(0)
                    val success = item?.optJSONObject("success")
                    val username = success?.optString("username").orEmpty()
                    if (username.isNotBlank()) {
                        val fp = fingerprint(it)
                        prefs.edit().putString("key_$host", username).putString("scheme_$host", scheme).apply {
                            if (fp != null) putString("fp_$host", fp)
                        }.apply()
                        callback(Result(true, "Hue Bridge авторизован. Application key сохранён на этом телефоне."))
                    } else {
                        val description = item?.optJSONObject("error")?.optString("description").orEmpty()
                        callback(Result(false, if (description.isBlank()) "Hue: авторизация не получена" else "Hue: $description"))
                    }
                }
            }
        })
    }

    private fun api(host: String, method: String, path: String, body: JSONObject?, callback: (Result, String?) -> Unit) {
        val appKey = prefs.getString("key_$host", null) ?: return callback(Result(false, "Сначала нажмите кнопку на Hue Bridge и авторизуйте приложение"), null)
        val scheme = prefs.getString("scheme_$host", "https") ?: "https"
        val builder = Request.Builder().url("$scheme://$host$path").header("hue-application-key", appKey)
        when (method) {
            "GET" -> builder.get()
            "PUT" -> builder.put((body ?: JSONObject()).toString().toRequestBody(jsonType))
            else -> return callback(Result(false, "Hue: неподдерживаемый метод"), null)
        }
        client.newCall(builder.build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = callback(Result(false, "Hue недоступен: ${e.message ?: e.javaClass.simpleName}"), null)
            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    val expected = prefs.getString("fp_$host", null)
                    val actual = fingerprint(it)
                    if (expected != null && actual != null && expected != actual) {
                        callback(Result(false, "Hue: сертификат Bridge изменился. Удалите сопряжение и авторизуйте Bridge заново."), null)
                        return
                    }
                    val text = it.body?.string().orEmpty()
                    if (!it.isSuccessful) callback(Result(false, "Hue HTTP ${it.code}: ${text.take(120)}"), text)
                    else callback(Result(true, "Hue: команда выполнена"), text)
                }
            }
        })
    }

    private fun fingerprint(response: Response): String? = runCatching {
        val cert = response.handshake?.peerCertificates?.firstOrNull() as? X509Certificate ?: return@runCatching null
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun close() { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
}
