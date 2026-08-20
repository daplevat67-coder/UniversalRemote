package com.example.universalremote.companion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CompanionCrypto {
    fun hmac(key: ByteArray, value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8))
    }

    fun encryptSessionPayload(key: ByteArray, plaintext: String): String {
        val aesKey = CompanionCrypto.hmac(key, "UniversalRemote Companion command encryption v2").copyOf(32)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"))
            b64(cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
        } finally {
            aesKey.fill(0)
        }
    }

    fun decryptSessionPayload(key: ByteArray, encoded: String): String {
        val raw = fromB64(encoded)
        require(raw.size > IV_BYTES) { "Некорректный encrypted payload" }
        val aesKey = CompanionCrypto.hmac(key, "UniversalRemote Companion command encryption v2").copyOf(32)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, raw.copyOfRange(0, IV_BYTES)))
            String(cipher.doFinal(raw.copyOfRange(IV_BYTES, raw.size)), Charsets.UTF_8)
        } finally {
            aesKey.fill(0)
        }
    }

    fun b64(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
    fun fromB64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
    fun constantTime(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
}

class CompanionSecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("companion_secure", Context.MODE_PRIVATE)
    private val alias = "com.example.universalremote.companion.secure"

    fun putBytes(key: String, value: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value)
        val raw = cipher.iv + encrypted
        prefs.edit().putString(key, Base64.encodeToString(raw, Base64.NO_WRAP)).apply()
    }

    fun getBytes(key: String): ByteArray? = runCatching {
        val raw = Base64.decode(prefs.getString(key, null) ?: return null, Base64.NO_WRAP)
        if (raw.size <= IV_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, IV_BYTES)))
        cipher.doFinal(raw.copyOfRange(IV_BYTES, raw.size))
    }.getOrNull()

    fun putString(key: String, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        try { putBytes(key, bytes) } finally { bytes.fill(0) }
    }

    fun getString(key: String): String? {
        val bytes = getBytes(key) ?: return null
        return try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) }
    }

    fun keys(prefix: String): Set<String> = prefs.all.keys.filterTo(linkedSetOf()) { it.startsWith(prefix) }
    fun remove(vararg keys: String) { prefs.edit().apply { keys.forEach(::remove) }.apply() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}
