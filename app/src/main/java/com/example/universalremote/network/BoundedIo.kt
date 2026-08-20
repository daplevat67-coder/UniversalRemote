package com.example.universalremote.network

import java.io.ByteArrayOutputStream
import java.io.InputStream

object BoundedIo {
    const val DEFAULT_MAX_BYTES = 256 * 1024

    fun readUtf8(input: InputStream?, maxBytes: Int = DEFAULT_MAX_BYTES): String {
        if (input == null) return ""
        val limit = maxBytes.coerceIn(1024, 2 * 1024 * 1024)
        val out = ByteArrayOutputStream(minOf(limit, 16 * 1024))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            require(total <= limit) { "Ответ устройства превышает $limit байт" }
            out.write(buffer, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Reads one CR/LF terminated line without ever buffering more than [maxBytes]. */
    fun readAsciiLine(input: InputStream, maxBytes: Int = 64 * 1024): String? {
        val limit = maxBytes.coerceIn(64, 256 * 1024)
        val out = ByteArrayOutputStream(minOf(limit, 1024))
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.US_ASCII.name())
            if (b == '\n'.code) return out.toString(Charsets.US_ASCII.name())
            if (b == '\r'.code) continue
            require(out.size() < limit) { "Строка ответа устройства превышает $limit байт" }
            out.write(b)
        }
    }
}
