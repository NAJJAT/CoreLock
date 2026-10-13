package com.privacyguard.vpn.mitm

import android.util.LruCache
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
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
        // Leaves live only in the in-memory cache; a short life limits what a leaked one is worth.
        private const val CERT_VALIDITY_DAYS = 30
        private const val MAX_CACHE_SIZE = 200

        private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

        /**
         * True if [sni] is a DNS hostname we may put in a certificate: ASCII
         * (punycode) labels, at least two of them, no IP literal (RFC 6066 forbids
         * those in SNI), no wildcard and nothing that could inject into the DN.
         */
        fun isValidHostname(sni: String): Boolean {
            if (sni.isEmpty() || sni.length > 253) return false
            val host = sni.lowercase(Locale.ROOT)
            val labels = host.split('.')
            if (labels.size < 2) return false
            if (labels.all { label -> label.all { it.isDigit() } }) return false   // IPv4 literal
            return labels.all { LABEL.matches(it) }
        }
    }

    /** [caSerial] identifies the CA that signed the leaf. */
    data class ForgedCert(val certificate: X509Certificate, val privateKey: PrivateKey, val caSerial: java.math.BigInteger?)

    private val cache = LruCache<String, ForgedCert>(MAX_CACHE_SIZE)
    private val lock = ReentrantReadWriteLock()

    fun forge(domain: String): Pair<X509Certificate, PrivateKey> {
        require(isValidHostname(domain)) { "refusing to forge a certificate for an invalid hostname" }
        // A leaf signed by a CA that has since been replaced (MITM switched off and on,
        // or the CA regenerated) would be rejected by every client; forge a new one.
        val caSerial = caManager.getCaCert()?.serialNumber
        lock.read {
            cache.get(domain)?.takeIf { it.caSerial == caSerial }?.let {
                Log.d(TAG, "Cache hit for domain: $domain")
                return it.certificate to it.privateKey
            }
        }

        Log.d(TAG, "Generating new certificate for domain: $domain")
        val result = generateDomainCertificate(domain)

        lock.write {
            cache.put(domain, ForgedCert(result.first, result.second, caSerial))
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
        // Built RDN by RDN: string parsing would let a crafted SNI add attributes.
        val subject = X500NameBuilder(BCStyle.INSTANCE)
            .addRDN(BCStyle.O, "PrivacyGuard")
            .addRDN(BCStyle.OU, "MITM Proxy")
            .addRDN(BCStyle.CN, domain)
            .build()
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

        // Exactly the requested host: a *.domain SAN would make one forged leaf
        // valid for every sibling host as well.
        val sanList = GeneralNames(GeneralName(GeneralName.dNSName, domain))
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