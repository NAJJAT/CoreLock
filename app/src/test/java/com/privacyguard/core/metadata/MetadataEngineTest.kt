package com.privacyguard.core.metadata

import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.session.Session
import com.privacyguard.core.session.SessionKey
import com.privacyguard.core.session.SessionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataEngineTest {

    private fun snap(
        ip: String, startedAt: Long, sni: String? = "api.example.com",
        out: Long = 100, inBytes: Long = 200, blocked: Boolean = false,
    ) = SessionSnapshot(
        key = SessionKey.of("10.0.0.2", 40000, ip, 443, IpPacket.PROTO_TCP),
        createdAt = startedAt, lastActivityAt = startedAt, ownerUid = 10100, ownerPackage = "com.app",
        bytesFromDevice = out, bytesToDevice = inBytes, packetsFromDevice = 1, packetsToDevice = 1,
        tcpState = Session.TcpState.CLOSED, hostname = sni, encryptionStatus = EncryptionStatus.TLS_1_3,
        tlsVersion = TlsVersion.TLS_1_3, tlsSni = sni, wasBackground = false, isClosed = true,
        blocked = blocked, isMitmIntercepted = false, payloadCount = 0,
    )

    private fun MetadataEngine.add(s: SessionSnapshot) = record(s, false, s.encryptionStatus, s.tlsVersion, s.tlsSni)

    @Test
    fun one_host_on_many_cdn_addresses_is_one_profile() {
        val e = MetadataEngine()
        e.add(snap("1.1.1.1", 1_000))
        e.add(snap("2.2.2.2", 61_000))
        e.add(snap("3.3.3.3", 121_000))
        val p = e.allProfiles().single()
        assertEquals("api.example.com", p.hostname)
        assertEquals(3L, p.connectionCount)
        assertEquals(300L, p.totalBytesOut)
    }

    @Test
    fun seeded_profiles_keep_accumulating_after_a_restart() {
        val before = MetadataEngine().apply { repeat(5) { add(snap("1.1.1.1", 1_000L + it * 60_000L)) } }
        val restarted = MetadataEngine().apply { seed(before.allProfiles()) }
        restarted.add(snap("1.1.1.1", 400_000))
        val p = restarted.allProfiles().single()
        assertEquals(6L, p.connectionCount)
        assertEquals(600L, p.totalBytesOut)
        assertEquals(1_000L, p.firstSeen)
    }

    @Test
    fun intervals_average_only_real_gaps_between_starts() {
        val e = MetadataEngine()
        e.add(snap("1.1.1.1", 0))
        e.add(snap("1.1.1.1", 30_000))
        e.add(snap("1.1.1.1", 90_000))   // gaps 30 s and 60 s
        val p = e.allProfiles().single()
        assertEquals(45_000L, p.avgIntervalMs)
        assertEquals(30_000L, p.minIntervalMs)
    }

    @Test
    fun blocked_attempts_are_not_behaviour() {
        val e = MetadataEngine()
        e.add(snap("1.1.1.1", 0, blocked = true, out = 0, inBytes = 0))
        assertTrue(e.allProfiles().isEmpty())
    }

    @Test
    fun without_sni_or_dns_name_the_address_is_the_host() {
        val e = MetadataEngine()
        e.add(snap("9.9.9.9", 0, sni = null))
        assertEquals("9.9.9.9", e.allProfiles().single().hostname)
    }

    @Test
    fun parent_domain_on_tracker_list_marks_subdomain() {
        val e = MetadataEngine().apply { knownTrackers = setOf("example.com") }
        e.add(snap("1.1.1.1", 0))
        assertTrue(e.allProfiles().single().riskSignals.any { it.code == RiskCode.KNOWN_TRACKER })
    }
}
