package com.privacyguard.vpn.forwarder

import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.packet.DnsPacket
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
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import java.io.FileDescriptor
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread

/**
 * Regression for V-01 (security test 2026-10-14): DoH must reject a certificate
 * that chains to a trusted CA but names another host, and must not send the query.
 * The test CA stands in for a public CA in the device trust store.
 */
class DohHostnameVerificationTest {

    companion object {
        private const val DAY = 86_400_000L
        private lateinit var ca: KeyPair
        private lateinit var caCert: X509Certificate
        private var previous: SSLContext? = null

        private fun rsa() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        private fun cert(cn: String, san: String?, keys: KeyPair, isCa: Boolean): X509Certificate {
            val now = System.currentTimeMillis()
            val b = JcaX509v3CertificateBuilder(X500Name("CN=Test CA"), BigInteger.valueOf(System.nanoTime()),
                Date(now - DAY), Date(now + DAY), X500Name("CN=$cn"), keys.public)
            b.addExtension(Extension.basicConstraints, true, BasicConstraints(isCa))
            if (san != null) b.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, san)))
            return JcaX509CertificateConverter().getCertificate(b.build(JcaContentSignerBuilder("SHA256withRSA").build(ca.private)))
        }

        @BeforeClass @JvmStatic fun trustTestCa() {
            ca = rsa()
            caCert = cert("Test CA", null, ca, true)
            val ts = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("ca", caCert) }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ts) }
            previous = SSLContext.getDefault()
            SSLContext.setDefault(SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) })
        }

        @AfterClass @JvmStatic fun restore() { previous?.let { SSLContext.setDefault(it) } }
    }

    /** One-shot DoH server for [certName]; records whether a request body arrived. */
    private fun server(certName: String, answer: ByteArray, sawQuery: AtomicBoolean): SSLServerSocket {
        val keys = rsa()
        val leaf = cert(certName, certName, keys, false)
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null); setKeyEntry("k", keys.private as PrivateKey, CharArray(0), arrayOf(leaf, caCert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val ss = ctx.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        thread(isDaemon = true) {
            runCatching {
                ss.accept().use { s ->
                    val input = s.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) { val c = input.read(); if (c < 0) return@use; head.append(c.toChar()) }
                    sawQuery.set(true)
                    s.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${answer.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(answer); flush()
                    }
                }
            }
        }
        return ss
    }

    private fun postDoh(certName: String): Pair<ByteArray?, Boolean> {
        val query = DnsPacket.buildQuery("bank.example", 0x1234)
        val answer = query.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte() }
        val saw = AtomicBoolean(false)
        val ss = server(certName, answer, saw)
        val handler = DnsHandler(FilterEngine(), TunWriter(TunInterface(FileDescriptor())), protectSocket = { true }, protectTcpSocket = { true })
        handler.dohProvider = "https://localhost:${ss.localPort}/dns-query"
        val m = DnsHandler::class.java.getDeclaredMethod("postDoh", ByteArray::class.java).apply { isAccessible = true }
        val reply = runCatching { m.invoke(handler, query) as ByteArray? }.getOrNull()
        ss.close()
        return reply to saw.get()
    }

    @Test fun certificate_for_another_host_is_rejected_before_the_query_is_sent() {
        val (reply, sawQuery) = postDoh("attacker.example")
        assertNull("reply accepted from a server with another host's certificate", reply)
        org.junit.Assert.assertFalse("query was sent to a server with another host's certificate", sawQuery)
    }

    @Test fun certificate_for_the_doh_host_is_accepted() {
        val (reply, sawQuery) = postDoh("localhost")
        assertNotNull(reply)
        org.junit.Assert.assertTrue(sawQuery)
    }
}
