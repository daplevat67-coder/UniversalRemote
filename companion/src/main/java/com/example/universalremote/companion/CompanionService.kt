package com.example.universalremote.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class CompanionService : Service() {
    data class PairedController(val clientId: String, val name: String, val expiresAt: Long)

    private val random = SecureRandom()
    private val workers = ThreadPoolExecutor(
        WORKERS, WORKERS, 20L, TimeUnit.SECONDS,
        ArrayBlockingQueue(WORK_QUEUE),
        ThreadPoolExecutor.AbortPolicy()
    )
    private val challenges = ConcurrentHashMap<String, Challenge>()
    private val pairAttempts = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val replayNonces = ConcurrentHashMap<String, Long>()
    private lateinit var secureStore: CompanionSecureStore
    private lateinit var nsd: NsdManager
    private lateinit var audio: AudioManager
    private lateinit var connectivity: ConnectivityManager
    private var server: ServerSocket? = null
    private var serverThread: Thread? = null
    private var registration: NsdManager.RegistrationListener? = null
    private lateinit var deviceId: String
    private lateinit var advertisementId: String

    override fun onCreate() {
        super.onCreate()
        secureStore = CompanionSecureStore(this)
        nsd = getSystemService(NsdManager::class.java)
        audio = getSystemService(AudioManager::class.java)
        connectivity = getSystemService(ConnectivityManager::class.java)
        deviceId = getSharedPreferences("companion_meta", MODE_PRIVATE).let { prefs ->
            prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }
        }
        advertisementId = UUID.randomUUID().toString()
        ensureChannel()
        rotatePairCode()
        refreshPairedSnapshot()
        startForeground(NOTIFICATION_ID, notification("Готов к сопряжению"))
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ROTATE -> rotatePairCode()
            ACTION_REVOKE -> intent.getStringExtra(EXTRA_CLIENT_ID)?.let(::revokeController)
            ACTION_REVOKE_ALL -> revokeAllControllers()
            ACTION_STOP -> stopSelf()
        }
        updateNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        currentPairCode = null
        currentPairCodeExpiresAt = 0L
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        runCatching { server?.close() }
        server = null
        serverThread?.interrupt()
        serverThread = null
        workers.shutdownNow()
        pairedControllers = emptyList()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        val wifiAddress = currentWifiLinks().firstOrNull()?.address as? Inet4Address
        if (wifiAddress == null) {
            currentStatus = "Нет активного Wi-Fi IPv4 — Companion не слушает сеть"
            updateNotification()
            return
        }
        serverThread = Thread({ runServer(wifiAddress) }, "uremote-companion-accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun runServer(wifiAddress: Inet4Address) {
        val socketResult = runCatching { ServerSocket(PORT, SERVER_BACKLOG, wifiAddress) }
        if (socketResult.isFailure) {
            currentStatus = "Порт $PORT занят/недоступен: ${socketResult.exceptionOrNull()?.message}"
            updateNotification()
            return
        }
        val socket = socketResult.getOrThrow()
        server = socket
        currentStatus = "Wi-Fi ${wifiAddress.hostAddress}:$PORT"
        registerNsd(PORT)
        updateNotification()
        while (!socket.isClosed && !Thread.currentThread().isInterrupted) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            try {
                workers.execute { handle(client) }
            } catch (_: RejectedExecutionException) {
                runCatching { client.close() }
            }
        }
    }

    private fun registerNsd(port: Int) {
        val service = NsdServiceInfo().apply {
            serviceName = "UniversalRemote ${Build.MODEL}"
            serviceType = SERVICE_TYPE
            this.port = port
            // Ephemeral advertisement identifier; the stable device ID is not broadcast in mDNS.
            setAttribute("id", advertisementId)
            setAttribute("model", Build.MODEL.take(40))
            setAttribute("v", "2")
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
            s.soTimeout = SOCKET_READ_TIMEOUT_MS
            val remote = s.inetAddress
            if (!isCurrentWifiPeer(remote)) return
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REQUEST_DEADLINE_MS)
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())
            val request = readRequest(input, deadline) ?: return
            val remoteText = remote.hostAddress ?: ""
            val response = when {
                request.method == "GET" && request.path == "/v2/info" -> infoResponse()
                request.method == "GET" && request.path == "/v2/challenge" -> challengeResponse(remoteText)
                request.method == "POST" && request.path == "/v2/pair" -> pairResponse(remoteText, request.body)
                request.method == "POST" && request.path == "/v2/command" -> commandResponse(request)
                request.method == "POST" && request.path == "/v2/revoke-self" -> revokeSelfResponse(request)
                else -> HttpResponse(404, json(false, "Неизвестный endpoint"))
            }
            writeResponse(output, response)
        }
    }

    private fun infoResponse(): HttpResponse {
        ensurePairCode()
        return HttpResponse(200, JSONObject().apply {
            put("ok", true)
            put("name", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            // v2 exposes only a per-service-start advertisement ID before pairing.
            put("advertisementId", advertisementId)
            put("version", 2)
            put("platform", "android")
            put("actions", org.json.JSONArray(listOf("volume_down", "mute", "volume_up", "media_previous", "media_play_pause", "media_next", "home", "back", "recents", "ping")))
            put("accessibility", CompanionAccessibilityService.isEnabled())
            put("pairCodeExpiresIn", ((currentPairCodeExpiresAt - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L))
            put("pairedControllers", pairedControllers.size)
        }.toString())
    }

    private fun challengeResponse(remote: String): HttpResponse {
        cleanupState()
        ensurePairCode()
        if (challenges.size >= MAX_CHALLENGES) return HttpResponse(429, json(false, "Слишком много незавершённых pairing challenge"))
        val id = randomToken(18)
        val nonce = randomBytes(32)
        challenges[id] = Challenge(remote, nonce, System.currentTimeMillis() + CHALLENGE_MS)
        return HttpResponse(200, JSONObject().apply {
            put("ok", true)
            put("challenge", id)
            put("serverNonce", CompanionCrypto.b64(nonce))
            put("advertisementId", advertisementId)
            put("expiresIn", CHALLENGE_MS / 1000)
        }.toString())
    }

    private fun pairResponse(remote: String, body: String): HttpResponse {
        cleanupState()
        if (!allowPairAttempt(remote)) return HttpResponse(429, json(false, "Слишком много попыток pairing; повторите позже"))
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return HttpResponse(400, json(false, "Некорректный JSON"))
        val challengeId = obj.optString("challenge")
        val clientNonceText = obj.optString("clientNonce")
        val proofText = obj.optString("proof")
        val clientId = obj.optString("clientId").take(80)
        val clientName = obj.optString("clientName").take(80).ifBlank { "UniversalRemote" }
        if (challengeId.isBlank() || clientNonceText.isBlank() || proofText.isBlank() || clientId.isBlank()) return HttpResponse(400, json(false, "Не хватает полей pairing"))
        val challenge = challenges.remove(challengeId) ?: return HttpResponse(401, json(false, "Pairing challenge истёк"))
        if (challenge.remote != remote || challenge.expiresAt < System.currentTimeMillis()) return HttpResponse(401, json(false, "Pairing challenge недействителен"))
        val code = validPairCode() ?: return HttpResponse(409, json(false, "Pairing-код истёк — нажмите «Новый pairing-код»"))
        val codeBytes = code.toByteArray(Charsets.UTF_8)
        val clientNonce = runCatching { CompanionCrypto.fromB64(clientNonceText) }.getOrNull() ?: return HttpResponse(400, json(false, "Некорректный client nonce"))
        if (clientNonce.size !in 16..64) return HttpResponse(400, json(false, "Некорректный client nonce"))
        val proof = runCatching { CompanionCrypto.fromB64(proofText) }.getOrNull() ?: return HttpResponse(400, json(false, "Некорректный proof"))
        val transcript = "pair\n$challengeId\n${CompanionCrypto.b64(challenge.nonce)}\n$clientNonceText\n$clientId\n$clientName"
        val expected = CompanionCrypto.hmac(codeBytes, transcript)
        if (!CompanionCrypto.constantTime(expected, proof)) {
            codeBytes.fill(0)
            return HttpResponse(401, json(false, "Неверный или истёкший pairing-код"))
        }
        val sessionTranscript = "session\n$challengeId\n${CompanionCrypto.b64(challenge.nonce)}\n$clientNonceText\n$clientId"
        val sessionKey = CompanionCrypto.hmac(codeBytes, sessionTranscript)
        codeBytes.fill(0)
        val expiresAt = System.currentTimeMillis() + SESSION_TTL_MS
        secureStore.putBytes(sessionKeyName(clientId), sessionKey)
        secureStore.putString(nameKey(clientId), clientName)
        secureStore.putString(expiryKey(clientId), expiresAt.toString())
        sessionKey.fill(0)
        rotatePairCode()
        refreshPairedSnapshot()
        return HttpResponse(200, JSONObject().apply {
            put("ok", true)
            put("message", "Сопряжение разрешено")
            // Stable ID is revealed only after proof of the on-screen pairing code.
            put("deviceId", deviceId)
            put("sessionExpiresAt", expiresAt)
        }.toString())
    }

    private fun commandResponse(request: HttpRequest): HttpResponse {
        val auth = authenticateAndDecrypt(request) ?: return HttpResponse(401, json(false, "Подпись/сессия команды недействительна"))
        val action = runCatching { JSONObject(auth.plaintext).optString("action") }.getOrDefault("")
        return executeAction(action)
    }

    private fun revokeSelfResponse(request: HttpRequest): HttpResponse {
        val auth = authenticateAndDecrypt(request) ?: return HttpResponse(401, json(false, "Подпись/сессия отзыва недействительна"))
        revokeController(auth.clientId)
        return HttpResponse(200, json(true, "Этот пульт отозван на Companion"))
    }

    private fun authenticateAndDecrypt(request: HttpRequest): AuthPayload? {
        cleanupState()
        val clientId = request.headers["x-ur-client"].orEmpty()
        val timestamp = request.headers["x-ur-timestamp"]?.toLongOrNull() ?: return null
        val nonce = request.headers["x-ur-nonce"].orEmpty()
        val signature = request.headers["x-ur-signature"].orEmpty()
        if (clientId.isBlank() || nonce.length !in 16..120 || signature.isBlank()) return null
        if (abs(System.currentTimeMillis() - timestamp) > COMMAND_SKEW_MS) return null
        val expiry = secureStore.getString(expiryKey(clientId))?.toLongOrNull() ?: return null
        if (expiry < System.currentTimeMillis()) {
            revokeController(clientId)
            return null
        }
        val replayKey = "$clientId:$nonce"
        val key = secureStore.getBytes(sessionKeyName(clientId)) ?: return null
        return try {
            val signed = "${request.method}\n${request.path}\n$timestamp\n$nonce\n${request.body}"
            val expected = CompanionCrypto.hmac(key, signed)
            val provided = runCatching { CompanionCrypto.fromB64(signature) }.getOrNull() ?: return null
            if (!CompanionCrypto.constantTime(expected, provided)) return null
            if (replayNonces.putIfAbsent(replayKey, System.currentTimeMillis()) != null) return null
            val wrapper = runCatching { JSONObject(request.body) }.getOrNull() ?: return null
            val payload = wrapper.optString("payload")
            if (payload.isBlank()) return null
            val plaintext = runCatching { CompanionCrypto.decryptSessionPayload(key, payload) }.getOrNull() ?: return null
            AuthPayload(clientId, plaintext)
        } finally {
            key.fill(0)
        }
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
        pairAttempts.entries.toList().forEach { entry ->
            val empty = synchronized(entry.value) {
                while (entry.value.isNotEmpty() && now - entry.value.first() > PAIR_WINDOW_MS) {
                    entry.value.removeFirst()
                }
                entry.value.isEmpty()
            }
            if (empty) pairAttempts.remove(entry.key, entry.value)
        }
        if (currentPairCodeExpiresAt in 1..now) rotatePairCode()
    }

    @Synchronized
    private fun rotatePairCode() {
        currentPairCode = newPairCode()
        currentPairCodeExpiresAt = System.currentTimeMillis() + PAIR_CODE_TTL_MS
        challenges.clear()
    }

    @Synchronized
    private fun ensurePairCode() {
        if (currentPairCode.isNullOrBlank() || currentPairCodeExpiresAt <= System.currentTimeMillis()) rotatePairCode()
    }

    @Synchronized
    private fun validPairCode(): String? = currentPairCode?.takeIf { currentPairCodeExpiresAt > System.currentTimeMillis() }

    private fun revokeController(clientId: String) {
        secureStore.remove(sessionKeyName(clientId), nameKey(clientId), expiryKey(clientId))
        replayNonces.keys.removeIf { it.startsWith("$clientId:") }
        refreshPairedSnapshot()
    }

    private fun revokeAllControllers() {
        secureStore.keys("session_").map { it.removePrefix("session_") }.forEach(::revokeController)
        refreshPairedSnapshot()
    }

    private fun refreshPairedSnapshot() {
        val now = System.currentTimeMillis()
        val current = mutableListOf<PairedController>()
        secureStore.keys("session_").forEach { key ->
            val clientId = key.removePrefix("session_")
            val expires = secureStore.getString(expiryKey(clientId))?.toLongOrNull() ?: 0L
            if (expires <= now) {
                secureStore.remove(sessionKeyName(clientId), nameKey(clientId), expiryKey(clientId))
            } else {
                current += PairedController(clientId, secureStore.getString(nameKey(clientId)).orEmpty().ifBlank { "UniversalRemote" }, expires)
            }
        }
        pairedControllers = current.sortedBy { it.name.lowercase() }
    }

    private fun readRequest(input: BufferedInputStream, deadlineNanos: Long): HttpRequest? {
        val first = readLine(input, 4096, deadlineNanos) ?: return null
        val parts = first.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        if (method !in setOf("GET", "POST")) return null
        val path = parts[1].substringBefore('?')
        val headers = linkedMapOf<String, String>()
        var headerCount = 0
        while (true) {
            val line = readLine(input, 4096, deadlineNanos) ?: return null
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
            if (System.nanoTime() > deadlineNanos) return null
            val n = input.read(body, offset, length - offset)
            if (n <= 0) return null
            offset += n
        }
        return HttpRequest(method, path, headers, String(body, Charsets.UTF_8))
    }

    private fun readLine(input: BufferedInputStream, max: Int, deadlineNanos: Long): String? {
        val out = StringBuilder()
        while (out.length < max) {
            if (System.nanoTime() > deadlineNanos) return null
            val b = input.read()
            if (b < 0) return if (out.isEmpty()) null else out.toString()
            if (b == '\n'.code) return out.toString()
            if (b != '\r'.code) out.append(b.toChar())
        }
        return null // fail closed if the line is not terminated before the bound
    }

    private fun writeResponse(out: BufferedOutputStream, response: HttpResponse) {
        val body = response.body.toByteArray(Charsets.UTF_8)
        val reason = when (response.code) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 409 -> "Conflict"; 429 -> "Too Many Requests"; else -> "Error" }
        val head = "HTTP/1.1 ${response.code} $reason\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8)); out.write(body); out.flush()
    }

    private fun isCurrentWifiPeer(address: InetAddress): Boolean {
        if (address.isLoopbackAddress) return true
        val target = address as? Inet4Address ?: return false
        return currentWifiLinks().any { link ->
            val local = link.address as? Inet4Address ?: return@any false
            samePrefix(local.address, target.address, link.prefixLength)
        }
    }

    private fun currentWifiLinks(): List<LinkAddress> = connectivity.allNetworks.asSequence()
        .filter { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        .flatMap { network -> connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().asSequence() }
        .filter { it.address is Inet4Address }
        .toList()

    private fun samePrefix(a: ByteArray, b: ByteArray, prefixLength: Int): Boolean {
        if (a.size != 4 || b.size != 4 || prefixLength !in 0..32) return false
        var bits = prefixLength
        for (i in 0 until 4) {
            if (bits <= 0) return true
            val take = minOf(8, bits)
            val mask = (0xFF shl (8 - take)) and 0xFF
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
            bits -= take
        }
        return true
    }

    private fun json(ok: Boolean, message: String): String = JSONObject().put("ok", ok).put("message", message).toString()
    private fun newPairCode(): String = buildString { repeat(12) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) } }
    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }
    private fun randomToken(bytes: Int): String = CompanionCrypto.b64(randomBytes(bytes)).replace("/", "_").replace("+", "-").trimEnd('=')
    private fun sessionKeyName(clientId: String) = "session_$clientId"
    private fun nameKey(clientId: String) = "name_$clientId"
    private fun expiryKey(clientId: String) = "expiry_$clientId"

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
    private data class AuthPayload(val clientId: String, val plaintext: String)

    companion object {
        const val PORT = 45123
        const val SERVICE_TYPE = "_uremote._tcp."
        const val ACTION_ROTATE = "com.example.universalremote.companion.ROTATE"
        const val ACTION_REVOKE = "com.example.universalremote.companion.REVOKE"
        const val ACTION_REVOKE_ALL = "com.example.universalremote.companion.REVOKE_ALL"
        const val ACTION_STOP = "com.example.universalremote.companion.STOP"
        const val EXTRA_CLIENT_ID = "client_id"
        private const val CHANNEL_ID = "companion_service"
        private const val NOTIFICATION_ID = 8001
        private const val PAIR_CODE_TTL_MS = 5 * 60_000L
        private const val CHALLENGE_MS = 90_000L
        private const val SESSION_TTL_MS = 30L * 24L * 60L * 60L * 1000L
        private const val COMMAND_SKEW_MS = 90_000L
        private const val PAIR_WINDOW_MS = 60_000L
        private const val MAX_PAIR_ATTEMPTS = 5
        private const val MAX_CHALLENGES = 32
        private const val MAX_BODY = 8192
        private const val WORKERS = 4
        private const val WORK_QUEUE = 16
        private const val SERVER_BACKLOG = 16
        private const val SOCKET_READ_TIMEOUT_MS = 1500
        private const val REQUEST_DEADLINE_MS = 4000L
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        @Volatile var currentPairCode: String? = null
        @Volatile var currentPairCodeExpiresAt: Long = 0L
        @Volatile var currentStatus: String? = null
        @Volatile var pairedControllers: List<PairedController> = emptyList()
    }
}
