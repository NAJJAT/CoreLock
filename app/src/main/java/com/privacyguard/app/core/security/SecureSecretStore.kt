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
    private val prefs: SharedPreferences,
) {
    companion object {
        private const val PREFS_NAME = "privacyguard_secure_store"
        private const val KEY_ALIAS = "pg_master_key_v1"
        private const val AES_MODE = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val KEY_INSTALLATION_SECRET = "installation_secret"
        // Separate Keystore key for secrets the VPN service needs while the screen
        // is locked (database passphrase, SIEM keys, VPN credentials), so it must
        // not require an unlocked device (the master key does).
        private const val BACKGROUND_KEY_ALIAS = "pg_background_key_v1"
        private const val KEY_DB_PASSPHRASE = "db_passphrase_v1"

        @Volatile
        private var INSTANCE: SecureSecretStore? = null

        fun getInstance(context: Context): SecureSecretStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecureSecretStore(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also { INSTANCE = it }
            }
        }
    }

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

    fun hasDatabasePassphrase(): Boolean = prefs.contains(KEY_DB_PASSPHRASE)

    /**
     * SQLCipher passphrase: 64 ASCII hex characters from 32 random bytes, wrapped
     * by [BACKGROUND_KEY_ALIAS]. The caller owns the returned array and should zero it.
     */
    @Synchronized
    fun getOrCreateDatabasePassphrase(): ByteArray {
        getBytes(KEY_DB_PASSPHRASE, BACKGROUND_KEY_ALIAS)?.let { return it }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hex = raw.joinToString("") { "%02x".format(it) }.toByteArray(StandardCharsets.US_ASCII)
        raw.fill(0)
        putBytes(KEY_DB_PASSPHRASE, hex.copyOf(), BACKGROUND_KEY_ALIAS, commit = true)
        return hex
    }

    /** Forgets the database passphrase; only for when the database itself is being deleted. */
    @Synchronized
    fun resetDatabasePassphrase() {
        prefs.edit().remove(KEY_DB_PASSPHRASE).commit()
    }

    /** A secret the VPN service may read while locked. An empty value removes it. */
    fun putBackgroundSecret(name: String, value: String) {
        if (value.isEmpty()) remove(name)
        else putBytes(name, value.toByteArray(StandardCharsets.UTF_8), BACKGROUND_KEY_ALIAS)
    }

    fun getBackgroundSecret(name: String): String? {
        val bytes = runCatching { getBytes(name, BACKGROUND_KEY_ALIAS) }.getOrNull() ?: return null
        return try {
            String(bytes, StandardCharsets.UTF_8)
        } finally {
            bytes.fill(0)
        }
    }

    /** Moves a secret that older versions kept in plain SharedPreferences into this store. */
    fun migratePlaintext(from: SharedPreferences, prefKey: String, name: String) {
        val legacy = from.getString(prefKey, null) ?: return
        if (legacy.isNotEmpty() && getBackgroundSecret(name) == null) putBackgroundSecret(name, legacy)
        from.edit().remove(prefKey).commit()
    }

    fun isHardwareBacked(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val keyStore = runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
            .getOrNull() ?: return false
        return runCatching { keyStore.containsAlias(KEY_ALIAS) }.getOrDefault(false)
    }

    private fun putBytes(name: String, value: ByteArray, alias: String = KEY_ALIAS, commit: Boolean = false) {
        val key = ensureKey(alias, requireUnlocked = alias == KEY_ALIAS)
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = try {
            cipher.doFinal(value)
        } finally {
            value.fill(0)
        }
        val payload = cipher.iv + encrypted
        val editor = prefs.edit().putString(name, Base64.encodeToString(payload, Base64.NO_WRAP))
        if (commit) editor.commit() else editor.apply()
        payload.fill(0)
        encrypted.fill(0)
    }

    private fun getBytes(name: String, alias: String = KEY_ALIAS): ByteArray? {
        val payload = prefs.getString(name, null) ?: return null
        val decoded = Base64.decode(payload, Base64.NO_WRAP)
        if (decoded.size <= 12) return null
        val iv = decoded.copyOfRange(0, 12)
        val ciphertext = decoded.copyOfRange(12, decoded.size)
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(
            Cipher.DECRYPT_MODE,
            ensureKey(alias, requireUnlocked = alias == KEY_ALIAS),
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

    private fun ensureKey(alias: String = KEY_ALIAS, requireUnlocked: Boolean = true): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = (keyStore.getKey(alias, null) as? SecretKey)
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)

        if (requireUnlocked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
        }

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }
}

