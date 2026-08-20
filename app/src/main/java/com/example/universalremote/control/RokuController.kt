package com.example.universalremote.control

import com.example.universalremote.network.BoundedIo
import com.example.universalremote.network.LocalEndpointPolicy
import com.example.universalremote.network.XmlSecurity
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class RokuController {
    data class Result(val ok: Boolean, val message: String)
    data class Info(val name: String, val model: String)
    private val executor = Executors.newFixedThreadPool(3)

    fun key(host: String, key: String, callback: (Result) -> Unit) = executor.execute {
        callback(runCatching {
            require(LocalEndpointPolicy.isPrivateIpv4(host)) { "Roku: разрешены только private LAN IPv4" }
            val conn = URL("http://$host:8060/keypress/$key").openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 1500; conn.readTimeout = 1800; conn.requestMethod = "POST"; conn.doOutput = true
            conn.outputStream.use { }
            val code = conn.responseCode
            when (code) {
                401, 403 -> error("Roku запретил ECP-команду. На Roku откройте Settings → System → Advanced system settings → Control by mobile apps и включите управление")
                !in 200..299 -> error("Roku ECP HTTP $code")
            }
            Result(true, "Roku: $key")
        }.getOrElse { Result(false, it.message ?: "Roku не ответил") })
    }

    fun probe(host: String, callback: (Result, Info?) -> Unit) = executor.execute {
        val result = runCatching {
            require(LocalEndpointPolicy.isPrivateIpv4(host)) { "Roku: разрешены только private LAN IPv4" }
            val conn = URL("http://$host:8060/query/device-info").openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 1400; conn.readTimeout = 1800; conn.requestMethod = "GET"
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val xml = conn.inputStream.use { BoundedIo.readUtf8(it) }
            val doc = XmlSecurity.factory().newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            fun tag(name: String) = doc.getElementsByTagName(name).item(0)?.textContent?.trim().orEmpty()
            val vendor = tag("vendor-name")
            if (!vendor.contains("Roku", true)) error("Порт 8060 отвечает, но это не Roku")
            Info(tag("user-device-name").ifBlank { "Roku" }, tag("model-name").ifBlank { tag("model-number") })
        }
        if (result.isSuccess) callback(Result(true, "Roku ECP доступен"), result.getOrNull())
        else callback(Result(false, result.exceptionOrNull()?.message ?: "Roku не обнаружен"), null)
    }

    fun close() = executor.shutdownNow()
}
