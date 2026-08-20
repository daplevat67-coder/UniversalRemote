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
}
