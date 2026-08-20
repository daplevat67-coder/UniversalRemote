package com.example.universalremote.network

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

class ServiceHealthProbe {
    data class Result(val attempts: Int, val successes: Int, val averageMs: Long?, val summary: String)
    private val executor = Executors.newSingleThreadExecutor()

    /** Low-rate availability probe: a few normal TCP connects, never malformed payloads or flooding. */
    fun check(host: String, port: Int, timeoutMs: Int, callback: (Result) -> Unit) {
        executor.execute {
            val samples = mutableListOf<Long>()
            repeat(3) {
                val started = System.nanoTime()
                val ok = runCatching {
                    Socket().use { socket -> socket.connect(InetSocketAddress(host, port), timeoutMs.coerceIn(80, 2000)) }
                    true
                }.getOrDefault(false)
                if (ok) samples += (System.nanoTime() - started) / 1_000_000L
                if (it < 2) Thread.sleep(150L)
            }
            val average = samples.takeIf { it.isNotEmpty() }?.average()?.toLong()
            callback(Result(3, samples.size, average, "TCP $host:$port — ${samples.size}/3 ответов" + (average?.let { ", среднее ${it} мс" } ?: "")))
        }
    }

    fun close() = executor.shutdownNow()
}
