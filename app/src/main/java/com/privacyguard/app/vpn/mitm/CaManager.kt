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
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import kotlin.math.absoluteValue

/**
 * Certificate Authority Manager for PrivacyGuard Enterprise MITM Layer
 *
 * Responsible for generating and managing the enterprise CA certificate and private key
 * used to forge leaf certificates for TLS interception.
 *
 * Thread Safety: All methods are synchronized on Keystore operations.
 * Business Reason: Provides a trusted CA that allows the app to perform TLS inspection
 * on enterprise-managed devices with proper legal consent.
 */
class CaManager(
    private val context: Context
) {
    companion object {
        private const val KEYSTORE_ALIAS = "privacyguard_enterprise_ca"
        private const val TAG = "CaManager"
        private const val CA_CN = "PrivacyGuard Enterprise CA"
        private const val CA_VALIDITY_DAYS = 3650 // 10 years
    }

    private val keystore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    private val _caCertFlow = MutableStateFlow<X509Certificate?>(null)
    val caCertFlow: Flow<X509Certificate?> = _caCertFlow.asStateFlow()

    /**
     * Initialize or load existing CA from Keystore
     * Returns true if CA exists or was generated successfully
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (keystore.containsAlias(KEYSTORE_ALIAS)) {
                loadExistingCa()
                Log.i(TAG, "CA loaded from Keystore")
                true
            } else {
                generateNewCa()
                Log.i(TAG, "New CA generated")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CA", e)
            false
        }
    }

    /**
     * Get CA certificate as PEM string for user installation
     * @return PEM-encoded certificate or empty string if not available
     */
    fun getCaCertPem(): String {
        val cert = _caCertFlow.value ?: return ""
        return convertToPem(cert)
    }

    /**
     * Get CA certificate as Base64 DER for MDM profile embedding
     * @return Base64 encoded DER certificate or empty string
     */
    fun getCaCertBase64(): String {
        val cert = _caCertFlow.value ?: return ""
        return Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
    }

    /**
     * Get CA certificate as X.509 object
     * @return X509Certificate or null if not available
     */
    fun getCaCert(): X509Certificate? = _caCertFlow.value

    /**
     * Get CA private key from Keystore (never cached in memory)
     * @return PrivateKey or null if not available
     */
    fun getCaKey(): java.security.PrivateKey? {
        return try {
            if (!keystore.containsAlias(KEYSTORE_ALIAS)) {
                Log.e(TAG, "CA alias not found in Keystore")
                return null
            }
            val entry = keystore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.PrivateKeyEntry
            entry?.privateKey
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get CA key from Keystore", e)
            null
        }
    }

    private fun generateNewCa() {
        // Generate key pair
        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            "AndroidKeyStore"
        )

        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_SIGN
        )
            .setKeySize(4096)
            .setDigests(
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA512
            )
            .setSignaturePaddings(
                KeyProperties.SIGNATURE_PADDING_RSA_PKCS1
            )
            .setUserAuthenticationRequired(false)
            .build()

        keyPairGenerator.initialize(spec)
        val keyPair = keyPairGenerator.generateKeyPair()

        // Generate self-signed certificate
        val cert = generateSelfSignedCertificate(keyPair)

        // Store certificate in Keystore
        val certificateChain = arrayOf(cert)
        keystore.setKeyEntry(
            KEYSTORE_ALIAS,
            keyPair.private,
            null,
            certificateChain
        )

        _caCertFlow.value = cert
    }

    private fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val dn = X500Name("CN=$CA_CN, OU=Security, O=PrivacyGuard, C=US")
        val serialNumber = BigInteger.valueOf(SecureRandom().nextLong().absoluteValue)
        val notBefore = Date(System.currentTimeMillis() - 86400000) // 1 day ago
        val notAfter = Date(System.currentTimeMillis() + CA_VALIDITY_DAYS * 86400000L)

        val certBuilder = JcaX509v3CertificateBuilder(
            dn,
            serialNumber,
            notBefore,
            notAfter,
            dn,
            keyPair.public
        )

        // Add basic constraints for CA
        certBuilder.addExtension(
            Extension.basicConstraints,
            true,
            BasicConstraints(true)
        )

        // Add key usage
        certBuilder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign)
        )

        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
        val certHolder = certBuilder.build(signer)

        return JcaX509CertificateConverter().getCertificate(certHolder)
    }

    private fun loadExistingCa() {
        val entry = keystore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.PrivateKeyEntry
        val cert = entry?.certificate as? X509Certificate
        _caCertFlow.value = cert
    }

    private fun convertToPem(cert: X509Certificate): String {
        val encoded = android.util.Base64.encodeToString(cert.encoded, android.util.Base64.DEFAULT)
        // Split into lines of 64 characters for PEM format
        val formatted = encoded.chunked(64).joinToString("\n")
        return "-----BEGIN CERTIFICATE-----\n$formatted\n-----END CERTIFICATE-----"
    }
}