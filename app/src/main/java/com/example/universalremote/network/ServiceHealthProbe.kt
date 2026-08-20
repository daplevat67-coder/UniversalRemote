package com.example.universalremote.network

import android.content.Context
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

class ServiceHealthProbe(context: Context) {
    data class Result(val attempts: Int, val successes: Int, val averageMs: Long?, val summary: String)
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()

    /** Low-rate availability probe: a few normal TCP connects, never malformed payloads or flooding. */
    fun check(host: String, port: Int, timeoutMs: Int, callback: (Result) -> Unit) {
        if (!LocalEndpointPolicy.isInCurrentWifiSubnet(appContext, host)) {
            return callback(Result(0, 0, null, "Проверка заблокирована: $host вне текущей Wi-Fi подсети"))
        }
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
