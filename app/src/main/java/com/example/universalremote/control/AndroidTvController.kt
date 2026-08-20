package com.example.universalremote.control

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * Android TV / Google TV Remote Service v2 client.
 * Pairing is performed with the six-character code displayed by the TV.
 */
class AndroidTvController(context: Context) {
    data class Result(val ok: Boolean, val message: String)

    private val prefs = context.getSharedPreferences("android_tv_pairing", Context.MODE_PRIVATE)
    private val executor = Executors.newCachedThreadPool()
    private val pairing = ConcurrentHashMap<String, PairingSession>()
    private val remotes = ConcurrentHashMap<String, RemoteSession>()
    private val identityAlias = "universal_remote_android_tv_identity_v1"

    init { ensureIdentity() }

    fun isPaired(host: String): Boolean = !prefs.getString(fpKey(host), null).isNullOrBlank()

    fun startPairing(host: String, callback: (Result) -> Unit) = executor.execute {
        runCatching {
            pairing.remove(host)?.close()
            remotes.remove(host)?.close()
            val socket = connectTls(host, 6467, pairingTrust())
            socket.soTimeout = 5000
            val session = PairingSession(socket)

            ProtoWire.writeFrame(session.output, pairOuter(10,
                ProtoWire.stringField(1, "atvremote"),
                ProtoWire.stringField(2, "Universal Remote")
            ))
            readPairField(session.input, 11)

            val encoding = ProtoWire.concat(ProtoWire.varintField(1, 3), ProtoWire.varintField(2, 6))
            ProtoWire.writeFrame(session.output, pairOuter(20,
                ProtoWire.messageField(1, encoding),
                ProtoWire.varintField(3, 1)
            ))
            readPairField(session.input, 20)

            ProtoWire.writeFrame(session.output, pairOuter(30,
                ProtoWire.messageField(1, encoding),
                ProtoWire.varintField(2, 1)
            ))
            readPairField(session.input, 31)

            pairing[host] = session
            callback(Result(true, "На телевизоре должен появиться 6-значный HEX-код. Введите его в приложении."))
        }.onFailure {
            pairing.remove(host)?.close()
            callback(Result(false, friendly(it)))
        }
    }

    fun finishPairing(host: String, code: String, callback: (Result) -> Unit) = executor.execute {
        val session = pairing[host]
        if (session == null) return@execute callback(Result(false, "Сначала нажмите «Начать сопряжение»"))
        runCatching {
            val normalized = code.trim().uppercase()
            require(normalized.matches(Regex("[0-9A-F]{6}"))) { "Код должен содержать ровно 6 символов 0–9/A–F" }
            val secret = pairingSecret(session.serverCertificate, normalized)
            ProtoWire.writeFrame(session.output, pairOuter(40, ProtoWire.bytesField(1, secret)))
            readPairField(session.input, 41)
            prefs.edit().putString(fpKey(host), fingerprint(session.serverCertificate)).apply()
            session.close(); pairing.remove(host)
            callback(Result(true, "Android TV сопряжён. Теперь пульт готов."))
        }.onFailure {
            session.close(); pairing.remove(host)
            callback(Result(false, friendly(it)))
        }
    }

    fun forget(host: String) {
        prefs.edit().remove(fpKey(host)).apply()
        pairing.remove(host)?.close()
        remotes.remove(host)?.close()
    }

    fun key(host: String, keyCode: Int, callback: (Result) -> Unit) = executor.execute {
        if (!isPaired(host)) return@execute callback(Result(false, "Сначала сопрягите Android TV по коду с экрана"))
        runCatching {
            val session = remoteSession(host)
            session.sendKey(keyCode)
            callback(Result(true, "Android TV: keycode $keyCode"))
        }.onFailure {
            remotes.remove(host)?.close()
            callback(Result(false, friendly(it)))
        }
    }

    private fun remoteSession(host: String): RemoteSession = synchronized(remotes) {
        remotes[host]?.takeIf { it.isAlive() } ?: RemoteSession(host).also { remotes[host] = it }
    }

    private inner class RemoteSession(private val host: String) {
        private val ready = CountDownLatch(1)
        @Volatile private var closed = false
        private val socket: SSLSocket = connectTls(host, 6466, pinnedTrust(host)).apply { soTimeout = 0 }
        private val input = socket.inputStream
        private val output = socket.outputStream
        @Volatile private var activeFeatures = REQUESTED_FEATURES

        init { executor.execute { readLoop() } }

        fun isAlive() = !closed && socket.isConnected && !socket.isClosed

        fun sendKey(keyCode: Int) {
            if (!ready.await(4, TimeUnit.SECONDS)) error("Android TV не завершил handshake Remote Service")
            if (closed) error("Android TV закрыл соединение")
            val inject = ProtoWire.concat(ProtoWire.varintField(1, keyCode.toLong()), ProtoWire.varintField(2, 3))
            ProtoWire.writeFrame(output, ProtoWire.messageField(10, inject))
        }

        private fun readLoop() {
            try {
                while (!closed) {
                    val payload = ProtoWire.readFrame(input)
                    for (field in ProtoWire.fields(payload)) {
                        when (field.number) {
                            1 -> handleConfigure(field.bytes ?: continue)
                            2 -> sendActive()
                            8 -> handlePing(field.bytes ?: continue)
                            40 -> ready.countDown()
                        }
                    }
                }
            } catch (_: Throwable) {
                close()
                remotes.remove(host, this)
            }
        }

        private fun handleConfigure(bytes: ByteArray) {
            val supported = ProtoWire.fields(bytes).firstOrNull { it.number == 1 }?.varint?.toInt() ?: REQUESTED_FEATURES
            activeFeatures = REQUESTED_FEATURES and supported
            if (activeFeatures and FEATURE_KEY == 0) error("TV не разрешает remote key injection")
            val info = ProtoWire.concat(
                ProtoWire.varintField(3, 1),
                ProtoWire.stringField(4, "1"),
                ProtoWire.stringField(5, "atvremote"),
                ProtoWire.stringField(6, "1.0.0")
            )
            val cfg = ProtoWire.concat(
                ProtoWire.varintField(1, activeFeatures.toLong()),
                ProtoWire.messageField(2, info)
            )
            ProtoWire.writeFrame(output, ProtoWire.messageField(1, cfg))
        }

        private fun sendActive() {
            val body = ProtoWire.varintField(1, activeFeatures.toLong())
            ProtoWire.writeFrame(output, ProtoWire.messageField(2, body))
        }

        private fun handlePing(bytes: ByteArray) {
            val value = ProtoWire.fields(bytes).firstOrNull { it.number == 1 }?.varint ?: 1L
            ProtoWire.writeFrame(output, ProtoWire.messageField(9, ProtoWire.varintField(1, value)))
        }

        fun close() {
            if (closed) return
            closed = true
            ready.countDown()
            runCatching { socket.close() }
        }
    }

    private class PairingSession(val socket: SSLSocket) {
        val input: InputStream = socket.inputStream
        val output: OutputStream = socket.outputStream
        val serverCertificate: X509Certificate = socket.session.peerCertificates.first() as X509Certificate
        fun close() = runCatching { socket.close() }.let { Unit }
    }

    private fun pairOuter(field: Int, vararg inner: ByteArray): ByteArray = ProtoWire.concat(
        ProtoWire.varintField(1, 2),
        ProtoWire.varintField(2, 200),
        ProtoWire.messageField(field, *inner)
    )

    private fun readPairField(input: InputStream, expected: Int) {
        repeat(8) {
            val fields = ProtoWire.fields(ProtoWire.readFrame(input))
            val status = fields.firstOrNull { it.number == 2 }?.varint?.toInt()
            if (status != null && status != 200) error("Android TV pairing status $status")
            if (fields.any { it.number == expected }) return
        }
        error("Android TV не прислал ожидаемый pairing message $expected")
    }

    private fun pairingSecret(server: X509Certificate, code: String): ByteArray {
        val client = clientCertificate()
        val clientKey = client.publicKey as? RSAPublicKey ?: error("Ключ клиента не RSA")
        val serverKey = server.publicKey as? RSAPublicKey ?: error("Ключ телевизора не RSA")
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(unsigned(clientKey.modulus))
        digest.update(unsigned(clientKey.publicExponent))
        digest.update(unsigned(serverKey.modulus))
        digest.update(unsigned(serverKey.publicExponent))
        digest.update(hex(code.substring(2)))
        val result = digest.digest()
        require((result[0].toInt() and 0xff) == code.substring(0, 2).toInt(16)) { "Неверный код сопряжения" }
        return result
    }

    private fun unsigned(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
    }

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { i -> value.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun connectTls(host: String, port: Int, trust: X509TrustManager): SSLSocket {
        val context = SSLContext.getInstance("TLS").apply { init(arrayOf<KeyManager>(keyManager()), arrayOf<TrustManager>(trust), SecureRandom()) }
        val plain = Socket()
        plain.connect(InetSocketAddress(host, port), 2600)
        val ssl = context.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        ssl.enabledProtocols = ssl.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        ssl.startHandshake()
        return ssl
    }

    private fun pairingTrust() = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun pinnedTrust(host: String): X509TrustManager {
        val expected = prefs.getString(fpKey(host), null) ?: error("Нет сохранённого отпечатка TV; выполните pairing")
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val cert = chain?.firstOrNull() ?: throw CertificateException("TV не прислал сертификат")
                if (!fingerprint(cert).equals(expected, ignoreCase = true)) throw CertificateException("Сертификат TV изменился — выполните pairing заново")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }

    private fun keyManager(): X509KeyManager {
        ensureIdentity()
        val ks = androidKeyStore()
        return object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(identityAlias)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = identityAlias
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate> = ks.getCertificateChain(identityAlias).map { it as X509Certificate }.toTypedArray()
            override fun getPrivateKey(alias: String?): PrivateKey = ks.getKey(identityAlias, null) as PrivateKey
        }
    }

    private fun clientCertificate(): X509Certificate = androidKeyStore().getCertificate(identityAlias) as X509Certificate

    private fun ensureIdentity() {
        val ks = androidKeyStore()
        if (ks.containsAlias(identityAlias)) return
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(identityAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setCertificateSubject(X500Principal("CN=Universal Remote"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(Date(now - 86_400_000L))
            .setCertificateNotAfter(Date(now + 10L * 365 * 86_400_000L))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
    }

    private fun androidKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun fingerprint(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun fpKey(host: String) = "server_fp_$host"
    private fun friendly(t: Throwable): String = when (t) {
        is CertificateException -> t.message ?: "Ошибка сертификата Android TV"
        else -> t.message ?: t.javaClass.simpleName
    }

    fun close() {
        pairing.values.forEach { it.close() }; pairing.clear()
        remotes.values.forEach { it.close() }; remotes.clear()
        executor.shutdownNow()
    }

    companion object {
        private const val FEATURE_PING = 1
        private const val FEATURE_KEY = 2
        private const val FEATURE_POWER = 32
        private const val FEATURE_VOLUME = 64
        private const val FEATURE_APP_LINK = 512
        private const val REQUESTED_FEATURES = FEATURE_PING or FEATURE_KEY or FEATURE_POWER or FEATURE_VOLUME or FEATURE_APP_LINK

        const val KEY_HOME = 3
        const val KEY_BACK = 4
        const val KEY_UP = 19
        const val KEY_DOWN = 20
        const val KEY_LEFT = 21
        const val KEY_RIGHT = 22
        const val KEY_OK = 23
        const val KEY_VOL_UP = 24
        const val KEY_VOL_DOWN = 25
        const val KEY_POWER = 26
        const val KEY_PLAY_PAUSE = 85
        const val KEY_REWIND = 89
        const val KEY_FAST_FORWARD = 90
        const val KEY_VOLUME_MUTE = 164
        const val KEY_SETTINGS = 176
        const val KEY_INPUT = 178
    }
}
