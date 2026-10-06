package com.privacyguard.vpn.mitm

import android.util.LruCache
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class CertForger(
    private val caManager: CaManager
) {
    companion object {
        private const val TAG = "CertForger"
        private const val CERT_VALIDITY_DAYS = 365
        private const val MAX_CACHE_SIZE = 200
    }

    data class ForgedCert(val certificate: X509Certificate, val privateKey: PrivateKey)

    private val cache = LruCache<String, ForgedCert>(MAX_CACHE_SIZE)
    private val lock = ReentrantReadWriteLock()

    fun forge(domain: String): Pair<X509Certificate, PrivateKey> {
        lock.read {
            cache.get(domain)?.let {
                Log.d(TAG, "Cache hit for domain: $domain")
                return it.certificate to it.privateKey
            }
        }

        Log.d(TAG, "Generating new certificate for domain: $domain")
        val result = generateDomainCertificate(domain)

        lock.write {
            cache.put(domain, ForgedCert(result.first, result.second))
        }

        return result
    }

    fun clearCache() {
        lock.write {
            cache.evictAll()
            Log.d(TAG, "Certificate cache cleared")
        }
    }

    private fun generateDomainCertificate(domain: String): Pair<X509Certificate, PrivateKey> {
        val caCert = caManager.getCaCert() ?: throw IllegalStateException("CA certificate not available")
        val caKey = caManager.getCaKey() ?: throw IllegalStateException("CA private key not available")

        val keyPair = generateKeyPair()
        val cert = buildCertificate(domain, keyPair.public, caCert, caKey)

        return cert to keyPair.private
    }

    // EC P-256 leaf keys: generated in ~1 ms versus hundreds of ms for RSA-2048,
    // which matters because the first connection to each domain forges on the
    // TUN-reader thread. The leaf is still signed by the RSA CA.
    private fun generateKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        return keyPairGenerator.generateKeyPair()
    }

    private fun buildCertificate(
        domain: String,
        publicKey: PublicKey,
        caCert: X509Certificate,
        caKey: PrivateKey
    ): X509Certificate {
        // Copy the CA subject's DER encoding verbatim. Parsing the RFC 2253 string
        // (X500Name(principal.name)) reverses the RDN order, so the leaf's issuer
        // would not match the installed CA and Android could never build a chain.
        val issuer = X500Name.getInstance(caCert.subjectX500Principal.encoded)
        val subject = X500Name("CN=$domain, OU=MITM Proxy, O=PrivacyGuard")
        val serialNumber = BigInteger(64, SecureRandom())
        val notBefore = Date(System.currentTimeMillis() - 86400000)
        val notAfter = Date(System.currentTimeMillis() + CERT_VALIDITY_DAYS * 86400000L)

        val certBuilder = JcaX509v3CertificateBuilder(
            issuer,
            serialNumber,
            notBefore,
            notAfter,
            subject,
            publicKey
        )

        // Subject Alternative Names (required for modern browsers)
        val sanList = GeneralNames(arrayOf(
            GeneralName(GeneralName.dNSName, domain),
            GeneralName(GeneralName.dNSName, "*.$domain")
        ))
        certBuilder.addExtension(Extension.subjectAlternativeName, false, sanList)

        // Basic Constraints - NOT a CA
        certBuilder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))

        // Key Usage
        certBuilder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature)   // EC keys sign; keyEncipherment is RSA-only
        )

        // Extended Key Usage - Server Authentication
        certBuilder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth)
        )

        // NO SubjectKeyIdentifier - not required
        // NO AuthorityKeyIdentifier - not required

        // Sign with CA key
        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(caKey)
        val certHolder = certBuilder.build(signer)

        return JcaX509CertificateConverter().getCertificate(certHolder)
    }
}