package com.privacyguard.app.ui.traffic

import com.privacyguard.app.core.tracker.DomainPurpose
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.db.DnsQueryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TrafficModelTest {

    private fun conn(
        id: Long, pkg: String = "com.instagram.android", sni: String? = "i.instagram.com", domain: String? = null,
        t: Long = 1_000_000L, fg: Boolean? = true, screen: Boolean? = true, enc: String = "TLS", sent: Long = 100,
    ) = ConnectionEntity(
        id = id, appUid = 10100, packageName = pkg, destinationIp = "157.240.1.1", destinationPort = 443,
        sniHostname = sni, domain = domain, timestamp = t, encryptionStatus = enc, bytesSent = sent, bytesReceived = 50,
        foreground = fg, screenOn = screen, wasBackground = fg == false,
    )

    @Test
    fun `source is sni then dns then ip`() {
        assertEquals(HostSource.TLS_SNI, TrafficRows.fromConnection(conn(1)).source)
        assertEquals(HostSource.DNS, TrafficRows.fromConnection(conn(2, sni = null, domain = "i.instagram.com")).source)
        val ipOnly = TrafficRows.fromConnection(conn(3, sni = null, domain = null))
        assertEquals(HostSource.IP_ONLY, ipOnly.source)
        assertEquals("157.240.1.1", ipOnly.ip)
    }

    @Test
    fun `first party traffic on screen is not flagged`() {
        val row = TrafficRows.fromConnection(conn(1))
        assertFalse(row.flagged)
        assertEquals(DomainPurpose.FIRST_PARTY, row.classification!!.purpose)
        assertTrue(row.encrypted)
    }

    @Test
    fun `reasons are what we observed`() {
        val row = TrafficRows.fromConnection(conn(1, pkg = "com.example.game", sni = "inapps.appsflyersdk.com", screen = false, fg = false, enc = "CLEARTEXT"))
        assertTrue(row.reasons.any { it is FlagReason.KnownTracker && it.name == "AppsFlyer" })
        assertTrue(FlagReason.ScreenOff in row.reasons)
        // Screen off already explains "not on screen"; don't list it twice.
        assertFalse(FlagReason.Background in row.reasons)
        assertTrue(FlagReason.Cleartext in row.reasons)
    }

    @Test
    fun `unknown foreground is never shown as background`() {
        val row = TrafficRows.fromConnection(conn(1, fg = null))
        assertEquals(null, row.foreground)
        assertFalse(FlagReason.Background in row.reasons)
    }

    @Test
    fun `blocked lookups are rows with dns source`() {
        val q = DnsQueryEntity(id = 9, timestamp = 5, appPackage = "Unknown", appName = "", domain = "an.facebook.com", wasBlocked = true, phoneWasIdle = true)
        val row = TrafficRows.fromLookup(q)
        assertTrue(row.isLookup)
        assertEquals("", row.packageName)
        assertEquals(DomainPurpose.ADS, row.classification!!.purpose)
        assertTrue(FlagReason.Blocked in row.reasons)
        assertTrue(FlagReason.ScreenOff in row.reasons)
    }

    @Test
    fun `purpose groups split meta core cdn and ads`() {
        val rows = listOf(
            TrafficRows.fromConnection(conn(1, sni = "i.instagram.com")),
            TrafficRows.fromConnection(conn(2, sni = "i.instagram.com")),
            TrafficRows.fromConnection(conn(3, sni = "scontent.cdninstagram.com")),
            TrafficRows.fromConnection(conn(4, sni = "graph.facebook.com")),
            TrafficRows.fromLookup(DnsQueryEntity(id = 5, timestamp = 1, appPackage = "com.instagram.android", appName = "Instagram", domain = "an.facebook.com", wasBlocked = true, phoneWasIdle = false)),
        )
        val groups = PurposeGroups.build(rows)
        assertEquals(listOf(DomainPurpose.FIRST_PARTY, DomainPurpose.ADS, DomainPurpose.CDN), groups.map { it.purpose })
        val core = groups.first()
        assertEquals(2, core.domains.size)               // i.instagram.com + graph.facebook.com
        assertEquals(3, core.connections)
        assertEquals("Meta Platforms", groups[1].company)
        assertEquals(1, groups[1].lookups)
    }

    @Test
    fun `home filters select exactly the matching rows`() {
        val night = Calendar.getInstance().apply { set(2026, 0, 10, 3, 0) }.timeInMillis
        val day = Calendar.getInstance().apply { set(2026, 0, 10, 14, 0) }.timeInMillis
        val list = listOf(
            conn(1, t = night, enc = "CLEARTEXT"),
            conn(2, t = day, fg = false),
            conn(3, t = day, fg = null, sent = 900),
            conn(4, t = day, sent = 0),
        )
        assertEquals(listOf(1L), HomeEvidence.select(EvidenceKind.NIGHT_24H, list).map { it.id })
        assertEquals(listOf(1L), HomeEvidence.select(EvidenceKind.CLEARTEXT_24H, list).map { it.id })
        assertEquals(listOf(2L), HomeEvidence.select(EvidenceKind.BACKGROUND_24H, list).map { it.id })
        // Largest upload first; connections that sent nothing are not uploads.
        assertEquals(listOf(3L, 1L, 2L), HomeEvidence.select(EvidenceKind.UPLOADS_24H, list).map { it.id })
    }

    @Test
    fun `beacon needs a steady timer`() {
        val steady = (0 until 8).map { conn(it.toLong(), t = 1_000_000L + it * 60_000L) }
        assertEquals(1, HomeEvidence.beacons(steady).size)
        val jittery = listOf(0L, 10_000L, 200_000L, 215_000L, 500_000L, 560_000L, 900_000L).mapIndexed { i, d -> conn(i.toLong(), t = 1_000_000L + d) }
        assertEquals(0, HomeEvidence.beacons(jittery).size)
    }
}
