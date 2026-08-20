package com.example.universalremote.security

import okhttp3.Response
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Explicit first-use certificate pinning for self-signed LAN appliances.
 *
 * Unknown certificates are NOT accepted by [trustManager]. A caller must first use
 * [inspectFingerprint] (TLS handshake only, no application credential) and ask the user to approve
 * the observed fingerprint with [approve]. This prevents silent first-use trust. It is still TOFU:
 * without a vendor-provided/out-of-band fingerprint, the first observation cannot cryptographically
 * prove device identity against an already-active MITM.
 */
class TofuTls(private val store: SecureStore, private val keyPrefix: String) {
    fun isPinned(host: String): Boolean = !store.getString(pinKey(host)).isNullOrBlank()
    fun pinnedFingerprint(host: String): String? = store.getString(pinKey(host))

    fun trustManager(host: String): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val cert = chain?.firstOrNull() ?: throw CertificateException("Устройство не прислало TLS-сертификат")
                val expected = store.getString(pinKey(host))
                    ?: throw CertificateException("TLS-сертификат ещё не подтверждён пользователем")
                if (!fingerprint(cert).equals(expected, ignoreCase = true)) {
                    throw CertificateException("TLS-сертификат устройства изменился — удалите сопряжение и подтвердите устройство заново")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }

    fun sslContext(host: String): SSLContext {
        val trust = trustManager(host)
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
    }

    /** Observe a certificate without sending any application-level credential or request payload. */
    fun inspectFingerprint(host: String, port: Int, timeoutMs: Int = 2500): String {
        require(port in 1..65535) { "Некорректный TLS-порт" }
        val permissive = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(permissive), SecureRandom()) }
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), timeoutMs.coerceIn(500, 5000))
        raw.soTimeout = timeoutMs.coerceIn(500, 5000)
        val tls = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        tls.use { socket ->
            socket.soTimeout = timeoutMs.coerceIn(500, 5000)
            socket.startHandshake()
            val cert = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: error("TLS peer certificate отсутствует")
            return fingerprint(cert)
        }
    }

    fun approve(host: String, fingerprint: String) {
        require(fingerprint.matches(Regex("[0-9a-fA-F]{64}"))) { "Некорректный SHA-256 fingerprint" }
        store.putString(pinKey(host), fingerprint.lowercase())
    }

    /** Never creates a first pin implicitly; only verifies an already-approved certificate. */
    fun pin(host: String, response: Response): Boolean {
        val expected = store.getString(pinKey(host)) ?: return false
        val cert = response.handshake?.peerCertificates?.firstOrNull() as? X509Certificate ?: return false
        return fingerprint(cert).equals(expected, ignoreCase = true)
    }

    fun pin(host: String, socket: SSLSocket): Boolean {
        val expected = store.getString(pinKey(host)) ?: return false
        val cert = socket.session.peerCertificates.firstOrNull() as? X509Certificate ?: return false
        return fingerprint(cert).equals(expected, ignoreCase = true)
    }

    /** Hostname verifier for self-signed LAN endpoints: the approved certificate pin is the identity. */
    fun verifyPinnedSession(host: String, session: SSLSession): Boolean {
        val expected = store.getString(pinKey(host)) ?: return false
        val cert = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull() ?: return false
        return fingerprint(cert).equals(expected, ignoreCase = true)
    }

    fun forget(host: String) = store.remove(pinKey(host))

    private fun pinKey(host: String) = "${keyPrefix}_fp_$host"

    companion object {
        fun fingerprint(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
