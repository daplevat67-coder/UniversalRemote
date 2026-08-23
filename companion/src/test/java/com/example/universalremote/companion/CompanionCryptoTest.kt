package com.example.universalremote.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionCryptoTest {
    @Test
    fun hmacIsDeterministicAndTranscriptBound() {
        val key = "pairing-secret".toByteArray()
        val a = CompanionCrypto.hmac(key, "pair\nchallenge-a")
        val b = CompanionCrypto.hmac(key, "pair\nchallenge-a")
        val c = CompanionCrypto.hmac(key, "pair\nchallenge-b")
        assertTrue(CompanionCrypto.constantTime(a, b))
        assertFalse(CompanionCrypto.constantTime(a, c))
    }

    @Test
    fun constantTimeRejectsDifferentLengths() {
        assertFalse(CompanionCrypto.constantTime(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
    }
}
