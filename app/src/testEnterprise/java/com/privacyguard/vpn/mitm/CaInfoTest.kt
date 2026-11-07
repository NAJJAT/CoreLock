package com.privacyguard.vpn.mitm

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Plan 2.3: [CaManager.infoOf] reads what [CaPolicy] decides on from real certificates,
 * built the way CaManager builds them. The rotation and the stale-certificate count both
 * depend on the subject's CN and O being read exactly.
 */
class CaInfoTest {

    private val day = 86_400_000L
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun cert(dn: String, days: Int, pathLen: Int? = 0, now: Long = System.currentTimeMillis()): X509Certificate {
        val name = X500Name(dn)
        val holder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(7), Date(now - day), Date(now + days * day), name, keys.public)
            .apply { if (pathLen != null) addExtension(Extension.basicConstraints, true, BasicConstraints(pathLen)) }
            .build(JcaContentSignerBuilder("SHA256WithRSA").build(keys.private))
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    @Test
    fun a_new_ca_is_read_as_ours_and_kept() {
        val info = CaManager.infoOf(cert("CN=${CaPolicy.CN}, O=${CaPolicy.ORG}, C=US", CaPolicy.VALIDITY_DAYS))
        assertEquals(CaPolicy.CN, info.cn)
        assertEquals(CaPolicy.ORG, info.org)
        assertEquals(0, info.pathLen)
        assertTrue(CaPolicy.isOurs(info.cn, info.org))
        assertFalse(CaPolicy.mustReplace(info, System.currentTimeMillis(), 0L, false))
    }

    @Test
    fun the_ca_of_1_1_is_read_as_ours_and_replaced() {
        val info = CaManager.infoOf(cert("CN=PrivacyGuard Enterprise CA, O=PrivacyGuard, C=US", 365))
        assertTrue(CaPolicy.isOurs(info.cn, info.org))
        assertTrue(CaPolicy.mustReplace(info, System.currentTimeMillis(), 0L, false))
    }

    @Test
    fun a_leaf_names_corelock_chains_to_the_ca_and_never_outlives_it() {
        val now = System.currentTimeMillis()
        val leafKeys = KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        // A CA ten days from its end: the 30-day leaf must stop when the CA does.
        val ending = cert("CN=${CaPolicy.CN}, O=${CaPolicy.ORG}, C=US", 10, now = now)
        val leaf = buildLeafCertificate("shop.example", leafKeys.public, ending, keys.private)
        assertTrue(leaf.notAfter.time <= ending.notAfter.time)
        leaf.verify(keys.public)
        assertEquals(ending.subjectX500Principal, leaf.issuerX500Principal)
        val subject = X500Name.getInstance(leaf.subjectX500Principal.encoded)
        assertEquals(CaPolicy.ORG, org.bouncycastle.asn1.x500.style.IETFUtils.valueToString(
            subject.getRDNs(org.bouncycastle.asn1.x500.style.BCStyle.O).first().first.value))
        assertFalse("PrivacyGuard" in leaf.subjectX500Principal.name)
        // A fresh CA: the leaf keeps its normal 30 days.
        val fresh = cert("CN=${CaPolicy.CN}, O=${CaPolicy.ORG}, C=US", CaPolicy.VALIDITY_DAYS, now = now)
        val normal = buildLeafCertificate("shop.example", leafKeys.public, fresh, keys.private)
        assertTrue(normal.notAfter.time - now in (CertForger.CERT_VALIDITY_DAYS * day - 60_000)..(CertForger.CERT_VALIDITY_DAYS * day + 60_000))
    }

    @Test
    fun another_ca_or_a_leaf_is_not_ours() {
        val other = CaManager.infoOf(cert("CN=Corporate Root CA, O=Example Corp", 365))
        assertFalse(CaPolicy.isOurs(other.cn, other.org))
        // A look-alike name in another attribute must not count.
        val lookAlike = CaManager.infoOf(cert("CN=Something, OU=CoreLock HTTPS Inspection CA, O=CoreLock", 90))
        assertFalse(CaPolicy.isOurs(lookAlike.cn, lookAlike.org))
        assertEquals(-1, CaManager.infoOf(cert("CN=leaf.example", 30, pathLen = null)).pathLen)
    }
}
