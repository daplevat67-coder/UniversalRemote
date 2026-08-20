package com.example.universalremote.control

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import javax.xml.parsers.DocumentBuilderFactory

class RokuController {
    data class Result(val ok: Boolean, val message: String)
    data class Info(val name: String, val model: String)
    private val executor = Executors.newCachedThreadPool()

    fun key(host: String, key: String, callback: (Result) -> Unit) = executor.execute {
        callback(runCatching {
            val conn = URL("http://$host:8060/keypress/$key").openConnection() as HttpURLConnection
            conn.connectTimeout = 1500; conn.readTimeout = 1800; conn.requestMethod = "POST"; conn.doOutput = true
            conn.outputStream.use { }
            val code = conn.responseCode
            if (code !in 200..299) error("Roku ECP HTTP $code")
            Result(true, "Roku: $key")
        }.getOrElse { Result(false, it.message ?: "Roku не ответил") })
    }

    fun probe(host: String, callback: (Result, Info?) -> Unit) = executor.execute {
        val result = runCatching {
            val conn = URL("http://$host:8060/query/device-info").openConnection() as HttpURLConnection
            conn.connectTimeout = 1400; conn.readTimeout = 1800; conn.requestMethod = "GET"
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val factory = DocumentBuilderFactory.newInstance().apply { runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } }
            val doc = conn.inputStream.use { factory.newDocumentBuilder().parse(it) }
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
