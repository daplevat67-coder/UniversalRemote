package com.example.universalremote.control

import android.content.Context
import android.util.Base64
import com.example.universalremote.network.BoundedIo
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
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CompanionController(context: Context) {
    data class Result(val ok: Boolean, val message: String, val info: Info? = null)
    data class Info(
        val name: String,
        /** Stable ID after pairing; before pairing this may be the ephemeral advertisement ID. */
        val deviceId: String,
        val advertisementId: String,
        val accessibility: Boolean,
        val version: Int,
        val pairCodeExpiresIn: Long,
        val platform: String = "android",
        val actions: Set<String> = emptySet()
    )

    private val appContext = context.applicationContext
    private val store = SecureStore(context, "companion_sessions")
    private val random = SecureRandom()
    private val http = OkHttpClient.Builder()
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(1800, TimeUnit.MILLISECONDS)
        .writeTimeout(1800, TimeUnit.MILLISECONDS)
        .callTimeout(3500, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun probe(host: String, port: Int, callback: (Result) -> Unit) {
        if (!LocalEndpointPolicy.isInCurrentWifiSubnet(appContext, host)) return callback(Result(false, "Companion разрешён только в текущей Wi-Fi подсети"))
        val req = Request.Builder().url("http://$host:$port/v2/info").get().build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result(false, "Companion не отвечает: ${e.message}"))
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = runCatching { BoundedIo.readUtf8(it.body?.byteStream(), 16_384) }.getOrElse { return callback(Result(false, "Companion прислал слишком большой ответ")) }
                    val obj = runCatching { JSONObject(body) }.getOrNull()
                    val info = obj?.let { json ->
                        val adId = json.optString("advertisementId")
                        if (adId.isBlank()) null else {
                            val stable = store.getString(hostMapKey(host, port)) ?: store.getString(aliasKey(adId)) ?: adId
                            Info(
                                json.optString("name", "Android Companion"), stable, adId,
                                json.optBoolean("accessibility"), json.optInt("version", 2),
                                json.optLong("pairCodeExpiresIn", 0L),
                                json.optString("platform", "android").lowercase(),
                                buildSet {
                                    val array = json.optJSONArray("actions")
                                    if (array != null) for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                                }
                            )
                        }
                    }
                    callback(if (it.isSuccessful && info != null && info.version >= 2) Result(true, "Companion API v2 подтверждён", info) else Result(false, "Это не UniversalRemote Companion v2"))
                }
            }
        })
    }

    fun pair(host: String, port: Int, expectedAdvertisementId: String?, code: CharArray, callback: (Result) -> Unit) {
        if (!LocalEndpointPolicy.isInCurrentWifiSubnet(appContext, host)) {
            code.fill('\u0000')
            return callback(Result(false, "Pairing разрешён только в текущей Wi-Fi подсети"))
        }
        val challengeReq = Request.Builder().url("http://$host:$port/v2/challenge").get().build()
        http.newCall(challengeReq).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                code.fill('\u0000'); callback(Result(false, "Не удалось начать pairing: ${e.message}"))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val obj = runCatching { JSONObject(BoundedIo.readUtf8(it.body?.byteStream(), 16_384)) }.getOrNull()
                    val challenge = obj?.optString("challenge").orEmpty()
                    val serverNonce = obj?.optString("serverNonce").orEmpty()
                    val advertisementId = obj?.optString("advertisementId").orEmpty()
                    if (!it.isSuccessful || challenge.isBlank() || serverNonce.isBlank() || advertisementId.isBlank()) {
                        code.fill('\u0000'); return callback(Result(false, obj?.optString("message").takeUnless { msg -> msg.isNullOrBlank() } ?: "Companion не выдал pairing challenge"))
                    }
                    if (!expectedAdvertisementId.isNullOrBlank() && expectedAdvertisementId != advertisementId) {
                        code.fill('\u0000'); return callback(Result(false, "Экземпляр Companion изменился; повторно откройте найденное устройство"))
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
                    val req = Request.Builder().url("http://$host:$port/v2/pair").post(body.toRequestBody(JSON)).build()
                    http.newCall(req).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            sessionKey.fill(0); callback(Result(false, "Pairing не завершён: ${e.message}"))
                        }
                        override fun onResponse(call: Call, pairResponse: Response) {
                            pairResponse.use { pr ->
                                val parsed = runCatching { JSONObject(BoundedIo.readUtf8(pr.body?.byteStream(), 16_384)) }.getOrNull()
                                val stableId = parsed?.optString("deviceId").orEmpty()
                                if (pr.isSuccessful && parsed?.optBoolean("ok") == true && stableId.isNotBlank()) {
                                    store.putString(sessionKeyName(stableId), b64(sessionKey))
                                    store.putString(aliasKey(advertisementId), stableId)
                                    store.putString(hostMapKey(host, port), stableId)
                                    parsed.optLong("sessionExpiresAt", 0L).takeIf { it > 0 }?.let { store.putString(expiryKey(stableId), it.toString()) }
                                    sessionKey.fill(0)
                                    callback(Result(true, "Companion сопряжён; серверный revoke и срок сессии включены.", Info("Companion", stableId, advertisementId, false, 2, 0L)))
                                } else {
                                    sessionKey.fill(0)
                                    callback(Result(false, parsed?.optString("message").takeUnless { it.isNullOrBlank() } ?: "Неверный/истёкший pairing-код"))
                                }
                            }
                        }
                    })
                }
            }
        })
    }

    fun command(host: String, port: Int, deviceId: String, action: String, callback: (Result) -> Unit) {
        signedEncryptedRequest(host, port, deviceId, "/v2/command", JSONObject().put("action", action).toString()) { result, code ->
            if (code == 401) forgetLocal(host, port, deviceId)
            callback(result)
        }
    }

    /** Revoke the controller on the managed phone first, then remove the local session. */
    fun forget(host: String, port: Int, deviceId: String, callback: (Result) -> Unit) {
        signedEncryptedRequest(host, port, deviceId, "/v2/revoke-self", JSONObject().put("revoke", true).toString()) { result, _ ->
            if (result.ok) forgetLocal(host, port, deviceId)
            callback(result)
        }
    }

    fun isPaired(deviceId: String?): Boolean {
        if (deviceId.isNullOrBlank()) return false
        val stable = store.getString(aliasKey(deviceId)) ?: deviceId
        val expiry = store.getString(expiryKey(stable))?.toLongOrNull()
        if (expiry != null && expiry < System.currentTimeMillis()) {
            store.remove(sessionKeyName(stable), expiryKey(stable))
            return false
        }
        return store.getString(sessionKeyName(stable)) != null
    }

    private fun signedEncryptedRequest(host: String, port: Int, deviceId: String, path: String, plaintext: String, callback: (Result, Int) -> Unit) {
        if (!LocalEndpointPolicy.isInCurrentWifiSubnet(appContext, host)) return callback(Result(false, "Команды Companion разрешены только в текущей Wi-Fi подсети"), 0)
        val stableId = resolveStableId(host, port, deviceId)
        val keyText = store.getString(sessionKeyName(stableId)) ?: return callback(Result(false, "Сначала выполните pairing с Companion"), 0)
        val key = runCatching { Base64.decode(keyText, Base64.NO_WRAP) }.getOrNull() ?: return callback(Result(false, "Повреждён ключ pairing; выполните pairing заново"), 0)
        val encrypted = runCatching { encryptSessionPayload(key, plaintext) }.getOrElse {
            key.fill(0); return callback(Result(false, "Не удалось зашифровать команду"), 0)
        }
        val body = JSONObject().put("payload", encrypted).toString()
        val timestamp = System.currentTimeMillis().toString()
        val nonce = b64(randomBytes(18))
        val signed = "POST\n$path\n$timestamp\n$nonce\n$body"
        val signature = b64(hmac(key, signed))
        key.fill(0)
        val req = Request.Builder().url("http://$host:$port$path")
            .header("X-UR-Client", clientId())
            .header("X-UR-Timestamp", timestamp)
            .header("X-UR-Nonce", nonce)
            .header("X-UR-Signature", signature)
            .post(body.toRequestBody(JSON)).build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result(false, "Companion не отвечает: ${e.message}"), 0)
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val obj = runCatching { JSONObject(BoundedIo.readUtf8(it.body?.byteStream(), 16_384)) }.getOrNull()
                    callback(Result(it.isSuccessful && obj?.optBoolean("ok") == true, obj?.optString("message").takeUnless { msg -> msg.isNullOrBlank() } ?: "HTTP ${it.code}"), it.code)
                }
            }
        })
    }

    private fun forgetLocal(host: String, port: Int, deviceId: String) {
        val stable = resolveStableId(host, port, deviceId)
        store.remove(sessionKeyName(stable), expiryKey(stable), hostMapKey(host, port), aliasKey(deviceId))
    }

    private fun resolveStableId(host: String, port: Int, id: String): String =
        store.getString(aliasKey(id)) ?: store.getString(hostMapKey(host, port)) ?: id

    private fun clientId(): String = store.getString("client_id") ?: UUID.randomUUID().toString().also { store.putString("client_id", it) }
    private fun sessionKeyName(deviceId: String) = "session_$deviceId"
    private fun expiryKey(deviceId: String) = "expiry_$deviceId"
    private fun aliasKey(advertisementId: String) = "alias_$advertisementId"
    private fun hostMapKey(host: String, port: Int) = "host_${host}_$port"
    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }
    private fun b64(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun hmac(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(value.toByteArray(Charsets.UTF_8))
    }

    private fun encryptSessionPayload(key: ByteArray, plaintext: String): String {
        val aesKey = hmac(key, "UniversalRemote Companion command encryption v2").copyOf(32)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"))
            b64(cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
        } finally {
            aesKey.fill(0)
        }
    }

    companion object {
        const val DEFAULT_PORT = 45123
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
