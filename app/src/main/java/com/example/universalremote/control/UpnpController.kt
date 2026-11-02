package com.example.universalremote.control

import com.example.universalremote.network.BoundedIo
import com.example.universalremote.network.LocalEndpointPolicy
import com.example.universalremote.network.XmlSecurity
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class UpnpController {
    data class Result(val ok: Boolean, val message: String)
    private data class Service(val type: String, val controlUrl: String, val host: String)
    private data class Services(val rendering: Service?, val transport: Service?)

    private val executor = Executors.newFixedThreadPool(3)
    private val cache = ConcurrentHashMap<String, Services>()

    fun probe(expectedHost: String, descriptionUrl: String, callback: (Result) -> Unit) = executor.execute {
        callback(runCatching {
            val found = services(expectedHost, descriptionUrl)
            if (found.rendering == null && found.transport == null) error("UPnP MediaRenderer services не найдены")
            Result(true, "UPnP MediaRenderer API подтверждён")
        }.getOrElse { Result(false, it.message ?: "UPnP descriptor недоступен") })
    }

    fun adjustVolume(expectedHost: String, descriptionUrl: String, delta: Int, callback: (Result) -> Unit) = executor.execute {
        val result = runCatching {
            val rendering = services(expectedHost, descriptionUrl).rendering ?: error("RenderingControl не найден")
            val current = getVolume(rendering).coerceIn(0, 100)
            val target = (current + delta).coerceIn(0, 100)
            soap(rendering, "SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$target</DesiredVolume>")
            Result(true, "Громкость: $target%")
        }.getOrElse { Result(false, it.message ?: "Ошибка UPnP") }
        callback(result)
    }

    fun setMute(expectedHost: String, descriptionUrl: String, mute: Boolean, callback: (Result) -> Unit) = executor.execute {
        val result = runCatching {
            val rendering = services(expectedHost, descriptionUrl).rendering ?: error("RenderingControl не найден")
            soap(rendering, "SetMute", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredMute>${if (mute) 1 else 0}</DesiredMute>")
            Result(true, if (mute) "Звук выключен" else "Звук включён")
        }.getOrElse { Result(false, it.message ?: "Ошибка UPnP") }
        callback(result)
    }

    fun media(expectedHost: String, descriptionUrl: String, action: String, callback: (Result) -> Unit) = executor.execute {
        val result = runCatching {
            val transport = services(expectedHost, descriptionUrl).transport ?: error("AVTransport не найден")
            when (action) {
                "Play" -> soap(transport, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
                "Pause" -> soap(transport, "Pause", "<InstanceID>0</InstanceID>")
                "Stop" -> soap(transport, "Stop", "<InstanceID>0</InstanceID>")
                else -> error("Неизвестная команда")
            }
            Result(true, "Команда $action отправлена")
        }.getOrElse { Result(false, it.message ?: "Ошибка UPnP") }
        callback(result)
    }

    fun close() = executor.shutdownNow()

    private fun services(expectedHost: String, descriptionUrl: String): Services {
        val key = "$expectedHost|$descriptionUrl"
        return cache[key] ?: discover(expectedHost, descriptionUrl).also { cache[key] = it }
    }

    private fun discover(expectedHost: String, descriptionUrl: String): Services {
        LocalEndpointPolicy.requireSamePrivateHost(expectedHost, descriptionUrl, setOf("http", "https"))
        val base = URI(descriptionUrl)
        val host = expectedHost
        val connection = URL(descriptionUrl).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 1800
        connection.readTimeout = 2200
        connection.requestMethod = "GET"
        val code = connection.responseCode
        if (code !in 200..299) error("UPnP descriptor HTTP $code")
        val text = connection.inputStream.use { BoundedIo.readUtf8(it) }
        val doc = XmlSecurity.factory().newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        val services = doc.getElementsByTagNameNS("*", "service")
        var rendering: Service? = null
        var transport: Service? = null
        for (i in 0 until services.length) {
            val node = services.item(i)
            val children = node.childNodes
            var type: String? = null
            var control: String? = null
            for (j in 0 until children.length) {
                val child = children.item(j)
                when (child.localName ?: child.nodeName.substringAfter(':')) {
                    "serviceType" -> type = child.textContent?.trim()
                    "controlURL" -> control = child.textContent?.trim()
                }
            }
            if (!type.isNullOrBlank() && !control.isNullOrBlank()) {
                val absolute = base.resolve(control).toString()
                LocalEndpointPolicy.requireSamePrivateHost(host, absolute, setOf("http", "https"))
                val service = Service(type, absolute, host)
                if ("RenderingControl" in type) rendering = service
                if ("AVTransport" in type) transport = service
            }
        }
        return Services(rendering, transport)
    }

    private fun getVolume(service: Service): Int {
        val response = soap(service, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>")
        return Regex("<(?:\\w+:)?CurrentVolume>(\\d+)</(?:\\w+:)?CurrentVolume>")
            .find(response)?.groupValues?.get(1)?.toIntOrNull() ?: 25
    }

    private fun soap(service: Service, action: String, inner: String): String {
        LocalEndpointPolicy.requireSamePrivateHost(service.host, service.controlUrl, setOf("http", "https"))
        val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:$action xmlns:u="${service.type}">$inner</u:$action></s:Body></s:Envelope>"""
        val bytes = body.toByteArray(Charsets.UTF_8)
        val connection = URL(service.controlUrl).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 1800
        connection.readTimeout = 2200
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        connection.setRequestProperty("SOAPAction", "\"${service.type}#$action\"")
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.use { BoundedIo.readUtf8(it) }.orEmpty()
        if (code !in 200..299) error("UPnP HTTP $code")
        return response
    }
}
