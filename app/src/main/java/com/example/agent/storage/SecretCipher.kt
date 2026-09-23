package com.example.agent.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (currently the model provider API key) before they are
 * written to Room, using a non-exportable AES-256 key that lives in the Android
 * Keystore and never leaves the device.
 *
 * Stored format:
 *
 *     enc:v1:<base64(iv)>:<base64(ciphertext)>
 *
 * Values without the `enc:v1:` prefix are treated as legacy plaintext and
 * returned unchanged, so databases created before this change keep working.
 * On a device where the Keystore is unavailable the value is stored as-is
 * instead of crashing the app.
 */
object SecretCipher {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "wagateway_agent_api_key"
    private const val PREFIX = "enc:v1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BITS = 128

    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty() || isEncrypted(plaintext)) return plaintext
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            PREFIX +
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        } catch (_: Exception) {
            // Keystore not available in this environment: keep the value usable.
            plaintext
        }
    }

    fun decrypt(stored: String): String {
        if (!isEncrypted(stored)) return stored
        return try {
            val parts = stored.removePrefix(PREFIX).split(":")
            if (parts.size != 2) return ""
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            // Key was invalidated (e.g. app data restored on another device).
            // The user simply re-enters the API key in Settings.
            ""
        }
    }

    private fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
