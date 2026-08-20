package com.example.universalremote.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs

class CompanionService : Service() {
    private val random = SecureRandom()
    private val workers = Executors.newFixedThreadPool(4)
    private val challenges = ConcurrentHashMap<String, Challenge>()
    private val pairAttempts = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val replayNonces = ConcurrentHashMap<String, Long>()
    private lateinit var secureStore: CompanionSecureStore
    private lateinit var nsd: NsdManager
    private lateinit var audio: AudioManager
    private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private lateinit var deviceId: String

    override fun onCreate() {
        super.onCreate()
        secureStore = CompanionSecureStore(this)
        nsd = getSystemService(NsdManager::class.java)
        audio = getSystemService(AudioManager::class.java)
        deviceId = getSharedPreferences("companion_meta", MODE_PRIVATE).let { prefs ->
            prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }
        }
        ensureChannel()
        currentPairCode = newPairCode()
        startForeground(NOTIFICATION_ID, notification("Готов к сопряжению"))
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ROTATE -> currentPairCode = newPairCode()
            ACTION_STOP -> stopSelf()
        }
        updateNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        currentPairCode = null
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        runCatching { server?.close() }
        server = null
        workers.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        workers.execute {
            val socket = runCatching { ServerSocket(PORT) }.getOrElse {
                currentStatus = "Порт $PORT занят: ${it.message}"
                updateNotification()
                return@execute
            }
            server = socket
            currentStatus = "Слушает LAN:$PORT"
            registerNsd(PORT)
            updateNotification()
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                workers.execute { handle(client) }
            }
        }
    }

    private fun registerNsd(port: Int) {
        val service = NsdServiceInfo().apply {
            serviceName = "UniversalRemote ${Build.MODEL}"
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("id", deviceId)
            setAttribute("model", Build.MODEL.take(40))
            setAttribute("v", "1")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) { currentStatus = "Обнаруживается по mDNS • $port" }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { currentStatus = "mDNS ошибка $errorCode • порт $port работает" }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        runCatching { nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 3500
            val remote = s.inetAddress
            if (!isLocal(remote)) return
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())
            val request = readRequest(input) ?: return
            val response = when {
                request.method == "GET" && request.path == "/v1/info" -> infoResponse()
                request.method == "GET" && request.path == "/v1/challenge" -> challengeResponse(remote.hostAddress ?: "")
                request.method == "POST" && request.path == "/v1/pair" -> pairResponse(remote.hostAddress ?: "", request.body)
                request.method == "POST" && request.path == "/v1/command" -> commandResponse(request)
                else -> HttpResponse(404, json(false, "Неизвестный endpoint"))
            }
            writeResponse(output, response)
        }
    }

    private fun infoResponse(): HttpResponse = HttpResponse(200, JSONObject().apply {
        put("ok", true)
        put("name", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        put("deviceId", deviceId)
        put("version", 1)
        put("accessibility", CompanionAccessibilityService.isEnabled())
    }.toString())

    private fun challengeResponse(remote: String): HttpResponse {
        cleanupState()
        if (challenges.size >= MAX_CHALLENGES) return HttpResponse(429, json(false, "Слишком много незавершённых pairing challenge"))
        val id = randomToken(18)
        val nonce = randomBytes(32)
        challenges[id] = Challenge(remote, nonce, System.currentTimeMillis() + CHALLENGE_MS)
        return HttpResponse(200, JSONObject().apply {
            put("ok", true)
            put("challenge", id)
            put("serverNonce", CompanionCrypto.b64(nonce))
            put("deviceId", deviceId)
            put("expiresIn", CHALLENGE_MS / 1000)
        }.toString())
    }

    private fun pairResponse(remote: String, body: String): HttpResponse {
        if (!allowPairAttempt(remote)) return HttpResponse(429, json(false, "Слишком много попыток pairing; повторите позже"))
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return HttpResponse(400, json(false, "Некорректный JSON"))
        val challengeId = obj.optString("challenge")
        val clientNonceText = obj.optString("clientNonce")
        val proofText = obj.optString("proof")
        val clientId = obj.optString("clientId").take(80)
        val clientName = obj.optString("clientName").take(80)
        if (challengeId.isBlank() || clientNonceText.isBlank() || proofText.isBlank() || clientId.isBlank()) return HttpResponse(400, json(false, "Не хватает полей pairing"))
        val challenge = challenges.remove(challengeId) ?: return HttpResponse(401, json(false, "Pairing challenge истёк"))
        if (challenge.remote != remote || challenge.expiresAt < System.currentTimeMillis()) return HttpResponse(401, json(false, "Pairing challenge недействителен"))
        val code = currentPairCode ?: return HttpResponse(409, json(false, "Pairing-код недоступен"))
        val codeBytes = code.toByteArray(Charsets.UTF_8)
        val clientNonce = runCatching { CompanionCrypto.fromB64(clientNonceText) }.getOrNull() ?: return HttpResponse(400, json(false, "Некорректный client nonce"))
        val proof = runCatching { CompanionCrypto.fromB64(proofText) }.getOrNull() ?: return HttpResponse(400, json(false, "Некорректный proof"))
        val transcript = "pair\n$challengeId\n${CompanionCrypto.b64(challenge.nonce)}\n$clientNonceText\n$clientId\n$clientName"
        val expected = CompanionCrypto.hmac(codeBytes, transcript)
        if (!CompanionCrypto.constantTime(expected, proof)) {
            codeBytes.fill(0)
            return HttpResponse(401, json(false, "Неверный pairing-код"))
        }
        val sessionTranscript = "session\n$challengeId\n${CompanionCrypto.b64(challenge.nonce)}\n$clientNonceText\n$clientId"
        val sessionKey = CompanionCrypto.hmac(codeBytes, sessionTranscript)
        codeBytes.fill(0)
        secureStore.putBytes("session_$clientId", sessionKey)
        sessionKey.fill(0)
        currentPairCode = newPairCode()
        updateNotification()
        return HttpResponse(200, JSONObject().apply {
            put("ok", true)
            put("message", "Сопряжение разрешено")
            put("deviceId", deviceId)
        }.toString())
    }

    private fun commandResponse(request: HttpRequest): HttpResponse {
        cleanupState()
        val clientId = request.headers["x-ur-client"].orEmpty()
        val timestamp = request.headers["x-ur-timestamp"]?.toLongOrNull() ?: return HttpResponse(401, json(false, "Нет timestamp"))
        val nonce = request.headers["x-ur-nonce"].orEmpty()
        val signature = request.headers["x-ur-signature"].orEmpty()
        if (clientId.isBlank() || nonce.length !in 16..120 || signature.isBlank()) return HttpResponse(401, json(false, "Нет подписи команды"))
        if (abs(System.currentTimeMillis() - timestamp) > COMMAND_SKEW_MS) return HttpResponse(401, json(false, "Команда просрочена"))
        val replayKey = "$clientId:$nonce"
        val key = secureStore.getBytes("session_$clientId") ?: return HttpResponse(401, json(false, "Этот пульт не сопряжён"))
        val signed = "POST\n/v1/command\n$timestamp\n$nonce\n${request.body}"
        val expected = CompanionCrypto.hmac(key, signed)
        key.fill(0)
        val provided = runCatching { CompanionCrypto.fromB64(signature) }.getOrNull() ?: return HttpResponse(401, json(false, "Некорректная подпись"))
        if (!CompanionCrypto.constantTime(expected, provided)) return HttpResponse(401, json(false, "Подпись команды не прошла проверку"))
        if (replayNonces.putIfAbsent(replayKey, System.currentTimeMillis()) != null) return HttpResponse(409, json(false, "Повтор команды отклонён"))
        val action = runCatching { JSONObject(request.body).optString("action") }.getOrDefault("")
        return executeAction(action)
    }

    private fun executeAction(action: String): HttpResponse {
        val ok = runCatching {
            when (action) {
                "volume_up" -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); true }
                "volume_down" -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); true }
                "mute" -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI); true }
                "media_play_pause" -> dispatchMedia(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                "media_next" -> dispatchMedia(KeyEvent.KEYCODE_MEDIA_NEXT)
                "media_previous" -> dispatchMedia(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "home" -> CompanionAccessibilityService.home()
                "back" -> CompanionAccessibilityService.back()
                "recents" -> CompanionAccessibilityService.recents()
                "ping" -> true
                else -> false
            }
        }.getOrDefault(false)
        val message = when {
            ok -> "Команда выполнена: $action"
            action in setOf("home", "back", "recents") -> "Включите Accessibility для Companion на управляемом телефоне"
            else -> "Команда не поддерживается"
        }
        return HttpResponse(if (ok) 200 else 409, json(ok, message))
    }

    private fun dispatchMedia(keyCode: Int): Boolean = runCatching {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        true
    }.getOrDefault(false)

    private fun allowPairAttempt(remote: String): Boolean {
        val now = System.currentTimeMillis()
        val q = pairAttempts.computeIfAbsent(remote) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first() > PAIR_WINDOW_MS) q.removeFirst()
            if (q.size >= MAX_PAIR_ATTEMPTS) return false
            q.addLast(now)
            return true
        }
    }

    private fun cleanupState() {
        val now = System.currentTimeMillis()
        challenges.entries.removeIf { it.value.expiresAt < now }
        replayNonces.entries.removeIf { now - it.value > 300_000L }
    }

    private fun readRequest(input: BufferedInputStream): HttpRequest? {
        val first = readLine(input, 4096) ?: return null
        val parts = first.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')
        val headers = linkedMapOf<String, String>()
        var headerCount = 0
        while (true) {
            val line = readLine(input, 4096) ?: return null
            if (line.isBlank()) break
            headerCount++
            if (headerCount > 32) return null
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 0..MAX_BODY) return null
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(body, offset, length - offset)
            if (n <= 0) return null
            offset += n
        }
        return HttpRequest(method, path, headers, String(body, Charsets.UTF_8))
    }

    private fun readLine(input: BufferedInputStream, max: Int): String? {
        val out = StringBuilder()
        while (out.length < max) {
            val b = input.read()
            if (b < 0) return if (out.isEmpty()) null else out.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) out.append(b.toChar())
        }
        return out.toString()
    }

    private fun writeResponse(out: BufferedOutputStream, response: HttpResponse) {
        val body = response.body.toByteArray(Charsets.UTF_8)
        val reason = when (response.code) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 409 -> "Conflict"; 429 -> "Too Many Requests"; else -> "Error" }
        val head = "HTTP/1.1 ${response.code} $reason\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8)); out.write(body); out.flush()
    }

    private fun isLocal(address: InetAddress): Boolean = address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress
    private fun json(ok: Boolean, message: String): String = JSONObject().put("ok", ok).put("message", message).toString()
    private fun newPairCode(): String = buildString { repeat(12) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) } }
    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }
    private fun randomToken(bytes: Int): String = CompanionCrypto.b64(randomBytes(bytes)).replace("/", "_").replace("+", "-").trimEnd('=')

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "UniversalRemote Companion", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(com.example.universalremote.companion.R.drawable.ic_companion)
        .setContentTitle("UniversalRemote Companion")
        .setContentText(text)
        .setOngoing(true)
        .build()

    private fun updateNotification() {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(currentStatus ?: "Готов к сопряжению")) }
    }

    private data class Challenge(val remote: String, val nonce: ByteArray, val expiresAt: Long)
    private data class HttpRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String)
    private data class HttpResponse(val code: Int, val body: String)

    companion object {
        const val PORT = 45123
        const val SERVICE_TYPE = "_uremote._tcp."
        const val ACTION_ROTATE = "com.example.universalremote.companion.ROTATE"
        const val ACTION_STOP = "com.example.universalremote.companion.STOP"
        private const val CHANNEL_ID = "companion_service"
        private const val NOTIFICATION_ID = 8001
        private const val CHALLENGE_MS = 120_000L
        private const val COMMAND_SKEW_MS = 120_000L
        private const val PAIR_WINDOW_MS = 60_000L
        private const val MAX_PAIR_ATTEMPTS = 5
        private const val MAX_CHALLENGES = 128
        private const val MAX_BODY = 8192
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        @Volatile var currentPairCode: String? = null
        @Volatile var currentStatus: String? = null
    }
}
