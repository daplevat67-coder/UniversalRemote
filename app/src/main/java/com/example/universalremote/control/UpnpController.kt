package com.example.universalremote.control

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.xml.parsers.DocumentBuilderFactory

class UpnpController {
    data class Result(val ok: Boolean, val message: String)
    private data class Service(val type: String, val controlUrl: String)
    private data class Services(val rendering: Service?, val transport: Service?)

    private val executor = Executors.newCachedThreadPool()
    private val cache = ConcurrentHashMap<String, Services>()

    fun adjustVolume(descriptionUrl: String, delta: Int, callback: (Result) -> Unit) = executor.execute {
        val result = runCatching {
            val services = services(descriptionUrl)
            val rendering = services.rendering ?: error("RenderingControl не найден")
            val current = getVolume(rendering).coerceIn(0, 100)
            val target = (current + delta).coerceIn(0, 100)
            soap(
                rendering,
                "SetVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$target</DesiredVolume>"
            )
            Result(true, "Громкость: $target%")
        }.getOrElse { Result(false, it.message ?: "Ошибка UPnP") }
        callback(result)
    }

    fun setMute(descriptionUrl: String, mute: Boolean, callback: (Result) -> Unit) = executor.execute {
        val result = runCatching {
            val rendering = services(descriptionUrl).rendering ?: error("RenderingControl не найден")
            soap(rendering, "SetMute", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredMute>${if (mute) 1 else 0}</DesiredMute>")
            Result(true, if (mute) "Звук выключен" else "Звук включён")
        }.getOrElse { Result(false, it.message ?: "Ошибка UPnP") }
        callback(result)
    }

    fun media(descriptionUrl: String, action: String, callback: (Result) -> Unit) = executor.execute {
        val result = runCatching {
            val transport = services(descriptionUrl).transport ?: error("AVTransport не найден")
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

    private fun services(descriptionUrl: String): Services = cache[descriptionUrl] ?: discover(descriptionUrl).also { cache[descriptionUrl] = it }

    private fun discover(descriptionUrl: String): Services {
        val connection = URL(descriptionUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 1800
        connection.readTimeout = 2200
        connection.requestMethod = "GET"
        val input = connection.inputStream
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val doc = input.use { factory.newDocumentBuilder().parse(it) }
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
                val absolute = URI(descriptionUrl).resolve(control).toString()
                val service = Service(type, absolute)
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
        val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:$action xmlns:u="${service.type}">$inner</u:$action></s:Body></s:Envelope>"""
        val bytes = body.toByteArray(Charsets.UTF_8)
        val connection = URL(service.controlUrl).openConnection() as HttpURLConnection
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
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error("UPnP HTTP $code")
        return response
    }
}
