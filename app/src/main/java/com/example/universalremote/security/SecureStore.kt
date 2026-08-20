package com.example.universalremote.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small AES-GCM store backed by a non-exportable Android Keystore key. */
class SecureStore(context: Context, name: String) {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val alias = "com.example.universalremote.secure.$name"

    fun getString(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        if (!stored.startsWith(PREFIX)) {
            // One-time migration from the v0.7.0 plaintext SharedPreferences value.
            putString(key, stored)
            return stored
        }
        return runCatching {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            require(raw.size > IV_BYTES)
            val iv = raw.copyOfRange(0, IV_BYTES)
            val cipherText = raw.copyOfRange(IV_BYTES, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrNull()
    }

    fun putString(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        require(iv.size == IV_BYTES)
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, blob, 0, iv.size)
        System.arraycopy(encrypted, 0, blob, iv.size, encrypted.size)
        prefs.edit().putString(key, PREFIX + Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun remove(vararg keys: String) {
        prefs.edit().apply { keys.forEach { this.remove(it) } }.apply()
    }

    fun contains(key: String): Boolean = getString(key) != null

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
        private const val PREFIX = "v1:"
        private const val IV_BYTES = 12
    }
}
