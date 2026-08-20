package com.example.universalremote.control

import android.content.Context
import android.util.Base64
import com.example.universalremote.network.LocalEndpointPolicy
import com.example.universalremote.security.SecureStore
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class CompanionController(context: Context) {
    data class Result(val ok: Boolean, val message: String, val info: Info? = null)
    data class Info(val name: String, val deviceId: String, val accessibility: Boolean, val version: Int)

    private val store = SecureStore(context, "companion_sessions")
    private val random = SecureRandom()
    private val http = OkHttpClient.Builder()
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(1800, TimeUnit.MILLISECONDS)
        .writeTimeout(1800, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun probe(host: String, port: Int, callback: (Result) -> Unit) {
        if (!LocalEndpointPolicy.isPrivateIpv4(host)) return callback(Result(false, "Companion разрешён только в private LAN"))
        val req = Request.Builder().url("http://$host:$port/v1/info").get().build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result(false, "Companion не отвечает: ${e.message}"))
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string()?.take(16_384).orEmpty()
                    val obj = runCatching { JSONObject(body) }.getOrNull()
                    val info = obj?.let { json ->
                        val id = json.optString("deviceId")
                        if (id.isBlank()) null else Info(json.optString("name", "Android Companion"), id, json.optBoolean("accessibility"), json.optInt("version", 1))
                    }
                    callback(if (it.isSuccessful && info != null) Result(true, "Companion API подтверждён", info) else Result(false, "Это не UniversalRemote Companion"))
                }
            }
        })
    }

    fun pair(host: String, port: Int, expectedDeviceId: String?, code: CharArray, callback: (Result) -> Unit) {
        if (!LocalEndpointPolicy.isPrivateIpv4(host)) {
            code.fill('\u0000')
            return callback(Result(false, "Pairing разрешён только в private LAN"))
        }
        val challengeReq = Request.Builder().url("http://$host:$port/v1/challenge").get().build()
        http.newCall(challengeReq).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                code.fill('\u0000'); callback(Result(false, "Не удалось начать pairing: ${e.message}"))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val obj = runCatching { JSONObject(it.body?.string()?.take(16_384).orEmpty()) }.getOrNull()
                    val challenge = obj?.optString("challenge").orEmpty()
                    val serverNonce = obj?.optString("serverNonce").orEmpty()
                    val deviceId = obj?.optString("deviceId").orEmpty()
                    if (!it.isSuccessful || challenge.isBlank() || serverNonce.isBlank() || deviceId.isBlank()) {
                        code.fill('\u0000'); return callback(Result(false, "Companion не выдал pairing challenge"))
                    }
                    if (!expectedDeviceId.isNullOrBlank() && expectedDeviceId != deviceId) {
                        code.fill('\u0000'); return callback(Result(false, "ID Companion изменился; повторно откройте найденное устройство"))
                    }
                    val clientId = clientId()
                    val clientName = "UniversalRemote Android"
                    val clientNonce = randomBytes(32)
                    val clientNonceText = b64(clientNonce)
                    val codeBytes = ByteArray(code.size) { i -> code[i].code.toByte() }
                    code.fill('\u0000')
                    val transcript = "pair\n$challenge\n$serverNonce\n$clientNonceText\n$clientId\n$clientName"
                    val proof = hmac(codeBytes, transcript)
                    val sessionTranscript = "session\n$challenge\n$serverNonce\n$clientNonceText\n$clientId"
                    val sessionKey = hmac(codeBytes, sessionTranscript)
                    codeBytes.fill(0)
                    val body = JSONObject().apply {
                        put("challenge", challenge)
                        put("clientNonce", clientNonceText)
                        put("proof", b64(proof))
                        put("clientId", clientId)
                        put("clientName", clientName)
                    }.toString()
                    val req = Request.Builder().url("http://$host:$port/v1/pair")
                        .post(body.toRequestBody(JSON)).build()
                    http.newCall(req).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            sessionKey.fill(0); callback(Result(false, "Pairing не завершён: ${e.message}"))
                        }
                        override fun onResponse(call: Call, pairResponse: Response) {
                            pairResponse.use { pr ->
                                val parsed = runCatching { JSONObject(pr.body?.string()?.take(16_384).orEmpty()) }.getOrNull()
                                if (pr.isSuccessful && parsed?.optBoolean("ok") == true) {
                                    store.putString(sessionKeyName(deviceId), b64(sessionKey))
                                    sessionKey.fill(0)
                                    callback(Result(true, "Companion сопряжён. PIN/пароль телефона не использовался."))
                                } else {
                                    sessionKey.fill(0)
                                    callback(Result(false, parsed?.optString("message").takeUnless { it.isNullOrBlank() } ?: "Неверный pairing-код"))
                                }
                            }
                        }
                    })
                }
            }
        })
    }

    fun command(host: String, port: Int, deviceId: String, action: String, callback: (Result) -> Unit) {
        if (!LocalEndpointPolicy.isPrivateIpv4(host)) return callback(Result(false, "Команды Companion разрешены только в private LAN"))
        val keyText = store.getString(sessionKeyName(deviceId)) ?: return callback(Result(false, "Сначала выполните pairing с Companion"))
        val key = runCatching { Base64.decode(keyText, Base64.NO_WRAP) }.getOrNull() ?: return callback(Result(false, "Повреждён ключ pairing; выполните pairing заново"))
        val body = JSONObject().put("action", action).toString()
        val timestamp = System.currentTimeMillis().toString()
        val nonce = b64(randomBytes(18))
        val signed = "POST\n/v1/command\n$timestamp\n$nonce\n$body"
        val signature = b64(hmac(key, signed))
        key.fill(0)
        val req = Request.Builder().url("http://$host:$port/v1/command")
            .header("X-UR-Client", clientId())
            .header("X-UR-Timestamp", timestamp)
            .header("X-UR-Nonce", nonce)
            .header("X-UR-Signature", signature)
            .post(body.toRequestBody(JSON)).build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result(false, "Команда Companion не дошла: ${e.message}"))
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val obj = runCatching { JSONObject(it.body?.string()?.take(16_384).orEmpty()) }.getOrNull()
                    callback(Result(it.isSuccessful && obj?.optBoolean("ok") == true, obj?.optString("message").takeUnless { msg -> msg.isNullOrBlank() } ?: "HTTP ${it.code}"))
                }
            }
        })
    }

    fun isPaired(deviceId: String?): Boolean = !deviceId.isNullOrBlank() && store.getString(sessionKeyName(deviceId)) != null
    fun forget(deviceId: String) = store.remove(sessionKeyName(deviceId))

    private fun clientId(): String = store.getString("client_id") ?: UUID.randomUUID().toString().also { store.putString("client_id", it) }
    private fun sessionKeyName(deviceId: String) = "session_$deviceId"
    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }
    private fun b64(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun hmac(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(value.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val DEFAULT_PORT = 45123
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
