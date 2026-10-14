package com.privacyguard.sectest

import com.privacyguard.core.packet.DnsPacket
import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.packet.Ipv6Packet
import com.privacyguard.core.packet.TcpPacket
import com.privacyguard.core.packet.UdpPacket
import com.privacyguard.core.session.Session
import com.privacyguard.core.session.SessionKey
import com.privacyguard.core.tls.ClientHelloParser
import com.privacyguard.core.tls.CtMonitor
import com.privacyguard.core.tls.Ja4Fingerprinter
import com.privacyguard.vpn.inspector.EncryptionEnforcer
import com.privacyguard.vpn.mitm.HttpCodec
import com.privacyguard.vpn.mitm.PayloadParser
import com.privacyguard.vpn.mitm.PiiRedactor
import org.junit.Test

/**
 * Security test pass (2026-10-14): entry points for standalone Jazzer
 * (jazzer --cp=<test classpath> --target_class=com.privacyguard.sectest.JzXxx).
 * Exceptions that production callers catch are swallowed here; Errors, hangs
 * (-timeout) and memory blowups (-rss_limit_mb) are findings.
 */
object JzPackets {
    @JvmStatic fun fuzzerTestOneInput(data: ByteArray) {
        IpPacket.parse(data)?.let { ip -> TcpPacket.parse(ip); UdpPacket.parse(ip) }
        Ipv6Packet.parse(data)?.let { v6 ->
            val end = minOf(data.size, Ipv6Packet.HEADER_LEN + v6.payloadLength)
            TcpPacket.parse(data, Ipv6Packet.HEADER_LEN, end)
            UdpPacket.parse(data, Ipv6Packet.HEADER_LEN, end)
        }
        DnsPacket.parse(data)?.let { p -> p.aRecords; p.aaaaRecords; p.cnameRecords; if (p.questions.isNotEmpty()) DnsPacket.buildBlockedResponse(p) }
    }
}

object JzTls {
    @JvmStatic fun fuzzerTestOneInput(data: ByteArray) {
        ClientHelloParser.parse(data)?.let { h -> h.ja3Hash(); Ja4Fingerprinter.ja4(h) }
        EncryptionEnforcer().inspect(443, data)
        EncryptionEnforcer().inspectUdp(data)
    }
}

object JzHttp {
    private val session = Session(SessionKey.of("10.0.0.2", 1, "1.2.3.4", 443, 6))
    private val parser = PayloadParser(PiiRedactor())
    @JvmStatic fun fuzzerTestOneInput(data: ByteArray) {
        HttpCodec.parseHead(data); HttpCodec.messageLength(data); HttpCodec.rewriteAcceptEncoding(data)
        HttpCodec.dechunk(data); HttpCodec.decompress(data, "gzip"); HttpCodec.decompress(data, "deflate")
        parser.redactForExport(parser.parse(data, "OUTBOUND", session))
    }
}

object JzCrtSh {
    @JvmStatic fun fuzzerTestOneInput(data: ByteArray) {
        try { CtMonitor.parse(String(data, Charsets.UTF_8)) } catch (_: Exception) { }
    }
}

/** Prints the unit-test classpath for the standalone Jazzer run. */
class JazzerClasspathDump {
    @Test fun dump() {
        val out = System.getenv("PG_CP_OUT") ?: return
        java.io.File(out).writeText(System.getProperty("java.class.path"))
    }
}
