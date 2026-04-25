package com.privacyguard.app.core.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSecretStore private constructor(
    private val context: Context,
) {
    companion object {
        private const val PREFS_NAME = "privacyguard_secure_store"
        private const val KEY_ALIAS = "pg_master_key_v1"
        private const val AES_MODE = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val KEY_INSTALLATION_SECRET = "installation_secret"

        @Volatile
        private var INSTANCE: SecureSecretStore? = null

        fun getInstance(context: Context): SecureSecretStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecureSecretStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun bootstrap() {
        ensureKey()
        if (getString(KEY_INSTALLATION_SECRET) == null) {
            val seed = ByteArray(32)
            SecureRandom().nextBytes(seed)
            putBytes(KEY_INSTALLATION_SECRET, seed)
            seed.fill(0)
        }
    }

    fun putString(name: String, value: String) {
        putBytes(name, value.toByteArray(StandardCharsets.UTF_8))
    }

    fun getString(name: String): String? {
        val bytes = getBytes(name) ?: return null
        return try {
            String(bytes, StandardCharsets.UTF_8)
        } finally {
            bytes.fill(0)
        }
    }

    fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    fun isHardwareBacked(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val keyStore = runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
            .getOrNull() ?: return false
        return runCatching { keyStore.containsAlias(KEY_ALIAS) }.getOrDefault(false)
    }

    private fun putBytes(name: String, value: ByteArray) {
        val key = ensureKey()
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = try {
            cipher.doFinal(value)
        } finally {
            value.fill(0)
        }
        val payload = cipher.iv + encrypted
        prefs.edit().putString(name, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
        payload.fill(0)
        encrypted.fill(0)
    }

    private fun getBytes(name: String): ByteArray? {
        val payload = prefs.getString(name, null) ?: return null
        val decoded = Base64.decode(payload, Base64.NO_WRAP)
        if (decoded.size <= 12) return null
        val iv = decoded.copyOfRange(0, 12)
        val ciphertext = decoded.copyOfRange(12, decoded.size)
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(
            Cipher.DECRYPT_MODE,
            ensureKey(),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        return try {
            cipher.doFinal(ciphertext)
        } finally {
            decoded.fill(0)
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun ensureKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
        }

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }
}
