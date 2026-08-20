package com.example.universalremote.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class NetworkParsingTest {
    @Test fun boundedLineStopsAtTerminator() {
        assertEquals("PJLINK 0", BoundedIo.readAsciiLine(ByteArrayInputStream("PJLINK 0\r\nrest".toByteArray()), 64))
    }

    @Test(expected = IllegalArgumentException::class)
    fun boundedLineRejectsOversize() {
        BoundedIo.readAsciiLine(ByteArrayInputStream(("A".repeat(200) + "\n").toByteArray()), 64)
    }

    @Test fun privateRangeClassification() {
        val privateRange = Ipv4Range.fromCidr("192.168.1.0/24")
        val publicRange = Ipv4Range.fromCidr("8.8.8.0/24")
        assertNotNull(privateRange)
        assertTrue(Ipv4Range.isPrivate(privateRange!!))
        assertNotNull(publicRange)
        assertFalse(Ipv4Range.isPrivate(publicRange!!))
    }
}
