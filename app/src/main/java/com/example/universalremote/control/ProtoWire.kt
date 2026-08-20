package com.example.universalremote.control

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream

/** Minimal protobuf wire codec for the small Android TV Remote v2 message subset we use. */
internal object ProtoWire {
    data class Field(val number: Int, val wireType: Int, val varint: Long? = null, val bytes: ByteArray? = null)

    fun varintField(number: Int, value: Long): ByteArray = concat(encodeVarint((number shl 3).toLong()), encodeVarint(value))
    fun bytesField(number: Int, value: ByteArray): ByteArray = concat(encodeVarint(((number shl 3) or 2).toLong()), encodeVarint(value.size.toLong()), value)
    fun stringField(number: Int, value: String): ByteArray = bytesField(number, value.toByteArray(Charsets.UTF_8))
    fun messageField(number: Int, vararg pieces: ByteArray): ByteArray = bytesField(number, concat(*pieces))
    fun concat(vararg pieces: ByteArray): ByteArray = ByteArrayOutputStream().use { out -> pieces.forEach(out::write); out.toByteArray() }

    fun encodeVarint(value: Long): ByteArray {
        var v = value
        val out = ByteArrayOutputStream()
        while (true) {
            if (v and -128L == 0L) { out.write(v.toInt()); break }
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        return out.toByteArray()
    }

    fun writeFrame(output: OutputStream, payload: ByteArray) {
        synchronized(output) {
            output.write(encodeVarint(payload.size.toLong()))
            output.write(payload)
            output.flush()
        }
    }

    fun readFrame(input: InputStream): ByteArray {
        val len = readVarint(input).toInt()
        require(len in 0..(1024 * 1024)) { "Некорректная длина protobuf: $len" }
        val data = ByteArray(len)
        var offset = 0
        while (offset < len) {
            val n = input.read(data, offset, len - offset)
            if (n < 0) throw EOFException("Соединение закрыто")
            offset += n
        }
        return data
    }

    fun fields(data: ByteArray): List<Field> {
        val result = mutableListOf<Field>()
        var pos = 0
        while (pos < data.size) {
            val key = readVarint(data, pos); pos = key.second
            val number = (key.first ushr 3).toInt()
            val wire = (key.first and 7).toInt()
            when (wire) {
                0 -> {
                    val v = readVarint(data, pos); pos = v.second
                    result += Field(number, wire, varint = v.first)
                }
                1 -> { pos += 8; require(pos <= data.size); result += Field(number, wire) }
                2 -> {
                    val l = readVarint(data, pos); pos = l.second
                    val len = l.first.toInt(); require(len >= 0 && pos + len <= data.size)
                    result += Field(number, wire, bytes = data.copyOfRange(pos, pos + len)); pos += len
                }
                5 -> { pos += 4; require(pos <= data.size); result += Field(number, wire) }
                else -> error("Неподдерживаемый protobuf wire type $wire")
            }
        }
        return result
    }

    private fun readVarint(input: InputStream): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            val b = input.read()
            if (b < 0) throw EOFException("Соединение закрыто")
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        error("Слишком длинный varint")
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = start
        while (shift < 64 && pos < data.size) {
            val b = data[pos++].toInt() and 0xff
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result to pos
            shift += 7
        }
        error("Некорректный varint")
    }
}
