package com.example.universalremote.security

import okhttp3.Response
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Trust-on-first-use TLS for self-signed LAN appliances.
 * Unknown certificates are accepted only until an application-level protocol succeeds;
 * callers then pin the observed SHA-256 fingerprint with [pin].
 */
class TofuTls(private val store: SecureStore, private val keyPrefix: String) {
    fun isPinned(host: String): Boolean = !store.getString(pinKey(host)).isNullOrBlank()

    fun trustManager(host: String): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val cert = chain?.firstOrNull() ?: throw CertificateException("Устройство не прислало TLS-сертификат")
                // Read on every handshake: the same OkHttp client becomes pinned immediately after first verified pairing.
                val expected = store.getString(pinKey(host))
                if (expected != null && !fingerprint(cert).equals(expected, ignoreCase = true)) {
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

    fun pin(host: String, response: Response): Boolean {
        val cert = response.handshake?.peerCertificates?.firstOrNull() as? X509Certificate ?: return false
        store.putString(pinKey(host), fingerprint(cert))
        return true
    }

    fun pin(host: String, socket: SSLSocket): Boolean {
        val cert = socket.session.peerCertificates.firstOrNull() as? X509Certificate ?: return false
        store.putString(pinKey(host), fingerprint(cert))
        return true
    }

    fun forget(host: String) = store.remove(pinKey(host))

    private fun pinKey(host: String) = "${keyPrefix}_fp_$host"

    companion object {
        fun fingerprint(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
