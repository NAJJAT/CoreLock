package com.privacyguard.sectest

import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.packet.DnsPacket
import com.privacyguard.vpn.forwarder.DnsHandler
import com.privacyguard.vpn.tunnel.TunInterface
import com.privacyguard.vpn.tunnel.TunWriter
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.AfterClass
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.FileDescriptor
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread

/**
 * Security test pass (2026-10-14).
 *
 * The test CA plays a public CA the device trusts. An attacker on the network who
 * owns a valid certificate for ANY name (here "attacker.example") and can redirect
 * the resolver's address must still be rejected: the name checked has to be the
 * DoH host / the SNI, not merely "some trusted certificate".
 */
class TlsValidationTest {

    companion object {
        private val day = 86_400_000L
        private lateinit var caKeys: KeyPair
        private lateinit var caCert: X509Certificate
        private var previousDefault: SSLContext? = null
        /** Device trust store stand-in (production: init(null, null) = system CAs). */
        lateinit var deviceTrust: Array<javax.net.ssl.TrustManager>

        private fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        private fun cert(subject: String, san: String?, keys: KeyPair, issuer: X500Name, signer: PrivateKey,
                         notBefore: Long, notAfter: Long, ca: Boolean): X509Certificate {
            val b = JcaX509v3CertificateBuilder(issuer, BigInteger.valueOf(System.nanoTime()), Date(notBefore), Date(notAfter),
                X500Name("CN=$subject"), keys.public)
            b.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
            if (san != null) b.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, san)))
            return JcaX509CertificateConverter().getCertificate(b.build(JcaContentSignerBuilder("SHA256withRSA").build(signer)))
        }

        @BeforeClass @JvmStatic fun trustTestCa() {
            caKeys = rsa()
            val now = System.currentTimeMillis()
            caCert = cert("Test Public CA", null, caKeys, X500Name("CN=Test Public CA"), caKeys.private, now - day, now + 30 * day, true)
            val ts = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("ca", caCert) }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ts) }
            deviceTrust = tmf.trustManagers
            previousDefault = SSLContext.getDefault()
            // Stands in for the device trust store: SSLSocketFactory.getDefault() and the
            // MITM engine's SSLContext.init(null, null, …) both use the default trust.
            SSLContext.setDefault(SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) })
        }

        @AfterClass @JvmStatic fun restore() { previousDefault?.let { SSLContext.setDefault(it) } }

        /** A one-shot TLS server presenting [leaf]; answers one HTTP request with [body]. */
        fun server(leaf: X509Certificate, key: PrivateKey, body: ByteArray, sawRequest: AtomicReference<String?>): SSLServerSocket {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                val chain = if (leaf.issuerX500Principal == caCert.subjectX500Principal) arrayOf(leaf, caCert) else arrayOf(leaf)
                setKeyEntry("k", key, CharArray(0), chain)
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
            val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
            val ss = ctx.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as SSLServerSocket
            thread(isDaemon = true) {
                runCatching {
                    ss.accept().use { s ->
                        val input = s.getInputStream()
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) { val c = input.read(); if (c < 0) break; head.append(c.toChar()) }
                        val len = Regex("Content-Length: (\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                        val req = ByteArray(len); var off = 0
                        while (off < len) { val n = input.read(req, off, len - off); if (n < 0) break; off += n }
                        sawRequest.set(head.lines().first() + " +" + len + "B dns query")
                        val out = s.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(body); out.flush()
                    }
                }
            }
            return ss
        }
    }

    private fun leaf(host: String, notBefore: Long, notAfter: Long): Pair<X509Certificate, PrivateKey> {
        val k = rsa()
        return cert(host, host, k, X500Name("CN=Test Public CA"), caKeys.private, notBefore, notAfter, false) to k.private
    }

    // ── DoH (DnsHandler.postDoh) ────────────────────────────────────────────

    @Test
    fun dohRejectsAValidCertificateForAnotherName() {
        val now = System.currentTimeMillis()
        val (cert, key) = leaf("attacker.example", now - day, now + day)
        val query = DnsPacket.buildQuery("bank.example", 0x1234)
        val forged = query.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte() }   // "response"
        val saw = AtomicReference<String?>(null)
        val ss = server(cert, key, forged, saw)

        val handler = DnsHandler(FilterEngine(), TunWriter(TunInterface(FileDescriptor())), protectSocket = { true }, protectTcpSocket = { true })
        // "localhost" stands for the DoH host; the attacker's certificate names attacker.example.
        handler.dohProvider = "https://localhost:${ss.localPort}/dns-query"
        val postDoh = DnsHandler::class.java.getDeclaredMethod("postDoh", ByteArray::class.java).apply { isAccessible = true }
        val reply = runCatching { postDoh.invoke(handler, query) as ByteArray? }
        ss.close()
        println("DoH to 'localhost' with a cert for 'attacker.example': request seen by attacker=${saw.get()}, " +
            "reply=${reply.getOrNull()?.size}B, error=${reply.exceptionOrNull()?.cause}")
        assertTrue("VULNERABLE: DoH accepted a certificate for another host and sent the query", saw.get() == null && reply.getOrNull() == null)
    }

    // ── MITM upstream (same TLS setup as MitmEngine.performInterception) ──

    /** Mirrors MitmEngine.createClientSslContext + the SSLParameters it sets. */
    private fun mitmUpstreamHandshake(port: Int, sni: String) {
        val ctx = SSLContext.getInstance("TLS").apply { init(null, deviceTrust, java.security.SecureRandom()) }
        val plain = java.net.Socket().apply { connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000) }
        val s = ctx.socketFactory.createSocket(plain, sni, port, true) as SSLSocket
        val p = s.sslParameters
        p.serverNames = listOf(SNIHostName(sni)); p.endpointIdentificationAlgorithm = "HTTPS"
        s.sslParameters = p
        s.use { it.startHandshake() }
    }

    private fun upstreamCase(name: String, cert: X509Certificate, key: PrivateKey, sni: String): Throwable? {
        val ss = server(cert, key, ByteArray(0), AtomicReference(null))
        val r = runCatching { mitmUpstreamHandshake(ss.localPort, sni) }.exceptionOrNull()
        ss.close()
        println("MITM upstream $name -> ${r?.javaClass?.simpleName ?: "ACCEPTED"}")
        return r
    }

    @Test fun mitmUpstreamRejectsWrongHost() {
        val now = System.currentTimeMillis()
        val (c, k) = leaf("other.example", now - day, now + day)
        assertNotNull(upstreamCase("wrong host", c, k, "bank.example") as? SSLException)
    }

    @Test fun mitmUpstreamRejectsExpired() {
        val now = System.currentTimeMillis()
        val (c, k) = leaf("bank.example", now - 10 * day, now - day)
        assertNotNull(upstreamCase("expired", c, k, "bank.example") as? SSLException)
    }

    @Test fun mitmUpstreamRejectsSelfSigned() {
        val now = System.currentTimeMillis()
        val kp = rsa()
        val self = cert("bank.example", "bank.example", kp, X500Name("CN=bank.example"), kp.private, now - day, now + day, false)
        assertNotNull(upstreamCase("self-signed", self, kp.private, "bank.example") as? SSLException)
    }

    @Test fun mitmUpstreamAcceptsValid() {
        val now = System.currentTimeMillis()
        val (c, k) = leaf("bank.example", now - day, now + day)
        assertTrue(upstreamCase("valid", c, k, "bank.example") == null)
    }
}
