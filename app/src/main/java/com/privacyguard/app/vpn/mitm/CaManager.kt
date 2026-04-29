package com.privacyguard.vpn.mitm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.cert.CertificateFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import kotlin.math.absoluteValue

/**
 * Manages the local CA key/cert pair used by the MITM engine.
 *
 * ## Key design decisions
 *
 * - Private key: always in AndroidKeyStore (hardware-backed, never leaves the device).
 * - Certificate: persisted in SharedPreferences as Base64 DER.
 *
 * Why not store the cert in AndroidKeyStore too?
 * On API 28+ calling `keystore.setKeyEntry(alias, privateKey, null, certChain)` after
 * `KeyPairGenerator.generateKeyPair()` throws `KeyStoreException: 2
 * (KEY_EXISTS_BUT_CANNOT_OVERWRITE)` because the key is already resident in hardware.
 * The auto-generated placeholder cert that Android attaches to the key has no subject
 * (null issuer), which produces the system error "This certificate from null must be
 * installed in Settings" when passed to `KeyChain.createInstallIntent()`.
 *
 * ## Threading
 * [initialize] is idempotent and serialised behind a Mutex. All other public methods
 * are safe to call from any thread once [initialize] has completed.
 */
class CaManager(private val context: Context) {

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG               = "CaManager"
        private const val KEY_ALIAS         = "privacyguard_enterprise_ca"
        private const val CA_CN             = "PrivacyGuard Enterprise CA"
        private const val CA_ORG            = "PrivacyGuard"
        private const val CA_COUNTRY        = "US"
        private const val CA_VALIDITY_DAYS  = 3650      // 10 years
        private const val PREFS_NAME        = "ca_manager_prefs"
        private const val PREFS_KEY_CERT    = "ca_cert_der_b64"
        private const val PREFS_KEY_SERIAL  = "ca_cert_serial"
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private val initMutex = Mutex()
    private val _caCertFlow = MutableStateFlow<X509Certificate?>(null)
    val caCertFlow: Flow<X509Certificate?> = _caCertFlow.asStateFlow()

    private val keystore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Ensures the CA key and certificate exist. Safe to call multiple times —
     * subsequent calls are no-ops if already initialised. Must be awaited before
     * calling [getCaCert] or [getCaKey].
     *
     * @return true if the CA is ready, false on unrecoverable error.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (_caCertFlow.value != null) {
                Log.d(TAG, "initialize: already initialised, skipping")
                return@withContext true
            }

            try {
                val keyExists  = keystore.containsAlias(KEY_ALIAS)
                val certExists = loadPersistedCert() != null

                Log.d(TAG, "initialize: keyExists=$keyExists certExists=$certExists")

                when {
                    keyExists && certExists -> {
                        // Happy path — both exist, load cert from SharedPreferences.
                        val cert = loadPersistedCert()!!
                        _caCertFlow.value = cert
                        Log.i(TAG, "initialize: CA loaded — subject=${cert.subjectDN} " +
                            "serial=${cert.serialNumber} encoded=${cert.encoded.size}B")
                        true
                    }

                    keyExists && !certExists -> {
                        // Key survived but cert was cleared (e.g. app data partial wipe).
                        // Regenerate everything from scratch.
                        Log.w(TAG, "initialize: key exists but cert is missing — regenerating")
                        deleteKey()
                        generateAndPersist()
                    }

                    !keyExists -> {
                        // Fresh install or full data clear.
                        Log.i(TAG, "initialize: no key found — generating new CA")
                        generateAndPersist()
                    }

                    else -> false   // unreachable
                }
            } catch (e: Exception) {
                Log.e(TAG, "initialize: FAILED — ${e.javaClass.simpleName}: ${e.message}", e)
                false
            }
        }
    }

    /**
     * Synchronous shortcut for callers that already know [initialize] has run
     * (e.g. from inside a coroutine launched after the VPN starts).
     * Returns null if [initialize] has not completed yet.
     */
    fun getCaCert(): X509Certificate? = _caCertFlow.value

    /**
     * Returns the CA private key from AndroidKeyStore. The key never leaves hardware.
     * Returns null if [initialize] has not completed or the key was deleted.
     */
    fun getCaKey(): java.security.PrivateKey? {
        return try {
            if (!keystore.containsAlias(KEY_ALIAS)) {
                Log.e(TAG, "getCaKey: alias '$KEY_ALIAS' not found in AndroidKeyStore")
                return null
            }
            val entry = keystore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            if (entry == null) {
                Log.e(TAG, "getCaKey: entry is null or wrong type")
            }
            entry?.privateKey
        } catch (e: Exception) {
            Log.e(TAG, "getCaKey: exception — ${e.message}", e)
            null
        }
    }

    /**
     * Returns the CA cert as a PEM string, or empty string if not ready.
     * Always check [getCaCert] != null before calling this.
     */
    fun getCaCertPem(): String {
        val cert = _caCertFlow.value ?: run {
            Log.w(TAG, "getCaCertPem: cert is null — was initialize() awaited?")
            return ""
        }
        val b64 = Base64.encodeToString(cert.encoded, Base64.DEFAULT)
        val lines = b64.chunked(64).joinToString("\n")
        return "-----BEGIN CERTIFICATE-----\n$lines\n-----END CERTIFICATE-----"
    }

    /**
     * Returns the CA cert as raw DER bytes, or empty array if not ready.
     * These are the bytes to pass to [android.security.KeyChain.createInstallIntent].
     */
    fun getCaCertDer(): ByteArray {
        val cert = _caCertFlow.value ?: run {
            Log.w(TAG, "getCaCertDer: cert is null — was initialize() awaited?")
            return ByteArray(0)
        }
        val der = cert.encoded
        Log.d(TAG, "getCaCertDer: returning ${der.size}B DER")
        return der
    }

    /** Base64 DER for MDM/EMM configuration profile embedding. */
    fun getCaCertBase64(): String {
        val der = getCaCertDer()
        if (der.isEmpty()) return ""
        return Base64.encodeToString(der, Base64.NO_WRAP)
    }

    /**
     * Deletes both the AndroidKeyStore key and the persisted cert.
     * The next [initialize] call will regenerate everything.
     */
    suspend fun reset() = withContext(Dispatchers.IO) {
        initMutex.withLock {
            deleteKey()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().apply()
            _caCertFlow.value = null
            Log.i(TAG, "reset: CA deleted")
        }
    }

    // ── Private: key generation ───────────────────────────────────────────────

    /**
     * Generates a new RSA key pair in AndroidKeyStore and a BouncyCastle CA cert,
     * then persists the cert DER bytes in SharedPreferences.
     *
     * Never calls `keystore.setKeyEntry()` — that throws
     * `KeyStoreException: KEY_EXISTS_BUT_CANNOT_OVERWRITE` on API 28+.
     */
    private fun generateAndPersist(): Boolean {
        // Step 1 — generate the RSA key pair inside AndroidKeyStore hardware.
        // KeyGenParameterSpec creates a placeholder cert automatically; we ignore it.
        val serial = BigInteger.valueOf(SecureRandom().nextLong().absoluteValue)
        val now    = System.currentTimeMillis()

        Log.d(TAG, "generateAndPersist: generating RSA-2048 key in AndroidKeyStore")

        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore"
        )
        kpg.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN,
            )
                .setKeySize(2048)       // 4096 is extremely slow on many devices
                .setDigests(
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA512,
                )
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setUserAuthenticationRequired(false)
                .setCertificateSerialNumber(serial)
                .setCertificateNotBefore(Date(now - 86_400_000L))
                .setCertificateNotAfter(Date(now + CA_VALIDITY_DAYS * 86_400_000L))
                .build()
        )
        val keyPair = kpg.generateKeyPair()
        Log.d(TAG, "generateAndPersist: key generated — public=${keyPair.public.algorithm}")

        // Step 2 — build a proper CA cert via BouncyCastle, signed with the hardware key.
        val dn   = X500Name("CN=$CA_CN, O=$CA_ORG, C=$CA_COUNTRY")
        val cert = JcaX509v3CertificateBuilder(
            /* issuer       */ dn,
            /* serial       */ serial,
            /* notBefore    */ Date(now - 86_400_000L),
            /* notAfter     */ Date(now + CA_VALIDITY_DAYS * 86_400_000L),
            /* subject      */ dn,
            /* publicKey    */ keyPair.public,
        ).apply {
            addExtension(Extension.basicConstraints, true,  BasicConstraints(true))
            addExtension(Extension.keyUsage,         true,
                KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        }.build(
            // keyPair.private is a hardware reference — BouncyCastle delegates to JCA
            // which routes through the AndroidKeyStore hardware signer.
            JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
        ).let { holder ->
            JcaX509CertificateConverter().getCertificate(holder)
        }

        Log.d(TAG, "generateAndPersist: BC cert built — subject=${cert.subjectDN} " +
            "encoded=${cert.encoded.size}B serial=${cert.serialNumber}")

        // Step 3 — persist the cert DER bytes. We CANNOT use setKeyEntry() here.
        persistCert(cert)
        _caCertFlow.value = cert

        Log.i(TAG, "generateAndPersist: ✅ CA ready")
        return true
    }

    // ── Private: cert persistence (SharedPreferences) ─────────────────────────

    private fun persistCert(cert: X509Certificate) {
        val b64 = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREFS_KEY_CERT,   b64)
            .putString(PREFS_KEY_SERIAL, cert.serialNumber.toString())
            .apply()
        Log.d(TAG, "persistCert: saved ${cert.encoded.size}B to SharedPreferences")
    }

    private fun loadPersistedCert(): X509Certificate? {
        val b64 = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREFS_KEY_CERT, null)
        if (b64.isNullOrBlank()) {
            Log.d(TAG, "loadPersistedCert: nothing stored")
            return null
        }
        return try {
            val der  = Base64.decode(b64, Base64.NO_WRAP)
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(der.inputStream()) as X509Certificate
            Log.d(TAG, "loadPersistedCert: parsed cert — subject=${cert.subjectDN} " +
                "encoded=${cert.encoded.size}B")
            cert
        } catch (e: Exception) {
            Log.e(TAG, "loadPersistedCert: corrupt DER in SharedPreferences — ${e.message}", e)
            // Remove the corrupted entry so the next initialize() regenerates cleanly.
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(PREFS_KEY_CERT).apply()
            null
        }
    }

    private fun deleteKey() {
        if (keystore.containsAlias(KEY_ALIAS)) {
            keystore.deleteEntry(KEY_ALIAS)
            Log.d(TAG, "deleteKey: deleted AndroidKeyStore entry '$KEY_ALIAS'")
        }
    }
}
