package com.privacyguard.vpn.forwarder

import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.session.BlockSource
import com.privacyguard.core.session.Session
import com.privacyguard.core.session.SessionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * An open connection is checked again by its name when rules or lists change (F-9):
 * the same NameBlockPolicy answer a new connection would get, with the layer that
 * read the name as the source.
 */
class NameRecheckTest {

    private val blocked = setOf("ads.example.com")
    private val policy: (String, String?) -> DnsBlockDecision.Reason? =
        { name, _ -> DnsBlockDecision.Reason.BLOCKLIST.takeIf { name in blocked } }

    private fun session(proto: Int) =
        Session(SessionKey.of("10.0.0.2", 40_000, "93.184.216.34", 443, proto), ownerUid = 10_123, ownerPackage = "com.example")

    @Test fun tcp_server_name_now_blocked_is_an_sni_block() {
        val s = session(IpPacket.PROTO_TCP).apply { tlsSni = "ads.example.com" }
        assertEquals(BlockSource.SNI, NameRecheck.sourceFor(s, policy))
    }

    @Test fun quic_server_name_now_blocked_is_a_quic_block() {
        val s = session(IpPacket.PROTO_UDP).apply { tlsSni = "ads.example.com" }
        assertEquals(BlockSource.QUIC, NameRecheck.sourceFor(s, policy))
    }

    @Test fun http_site_name_now_blocked_is_an_http_block() {
        val s = session(IpPacket.PROTO_TCP).apply { httpHost = "ads.example.com" }
        assertEquals(BlockSource.HTTP, NameRecheck.sourceFor(s, policy))
    }

    @Test fun allowed_or_nameless_connections_go_on() {
        assertNull(NameRecheck.sourceFor(session(IpPacket.PROTO_TCP).apply { tlsSni = "news.example.org" }, policy))
        assertNull(NameRecheck.sourceFor(session(IpPacket.PROTO_TCP), policy))
        // A name only DNS gave this address out for is not checked: addresses are shared.
        assertNull(NameRecheck.sourceFor(session(IpPacket.PROTO_TCP).apply { resolvedHostname = "ads.example.com" }, policy))
    }

    @Test fun the_app_is_passed_to_the_policy() {
        var seen: String? = "unset"
        NameRecheck.sourceFor(session(IpPacket.PROTO_TCP).apply { tlsSni = "x.example" }) { _, pkg -> seen = pkg; null }
        assertEquals("com.example", seen)
    }
}
