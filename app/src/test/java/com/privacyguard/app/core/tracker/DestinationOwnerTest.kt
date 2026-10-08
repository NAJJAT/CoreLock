package com.privacyguard.app.core.tracker

import com.privacyguard.app.core.detection.ObservedDestination
import com.privacyguard.app.core.detection.PermissionMismatchDetector
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.db.DnsQueryEntity
import com.privacyguard.app.ui.apps.TopologyBuilder
import com.privacyguard.app.ui.apps.TopologyState
import com.privacyguard.core.metadata.EncryptionStatus
import com.privacyguard.core.session.DnsNameCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationOwnerTest {

    @Test
    fun metaServersAreWhatsAppsOwnNotAnSdk() {
        val info = DestinationOwner.describe("z-m-gateway.facebook.com", "157.240.1.1", "com.whatsapp")
        assertEquals(DestinationOwner.Role.FIRST_PARTY, info.role)
        assertEquals("Meta", info.owner)
        assertNull(info.trackerName)
    }

    @Test
    fun anUnnamedAddressInTheAppsOwnRangeIsFirstParty() {
        val info = DestinationOwner.describe(null, "179.60.195.51", "com.whatsapp")
        assertEquals(DestinationOwner.Role.FIRST_PARTY, info.role)
        assertEquals("Meta", info.owner)
    }

    @Test
    fun whatsappHostsAreNamedNotGenericNetwork() {
        val info = DestinationOwner.describe("media-bru2-1.cdn.whatsapp.net", "157.240.1.1", null)
        assertEquals("Meta", info.owner)
        assertEquals(DestinationOwner.Role.THIRD_PARTY, info.role)
    }

    @Test
    fun hostingProvidersAreRecognised() {
        assertEquals(DestinationOwner.Role.HOSTING, DestinationOwner.describe("d1.cloudfront.net", "1.2.3.4").role)
    }

    @Test
    fun anUnlistedSiteIsNamedByItsDomain() {
        val info = DestinationOwner.describe("static.files.bbci.co.uk", "203.0.113.9")
        assertEquals("bbci.co.uk", info.owner)
        assertEquals(DestinationOwner.Role.UNLISTED, info.role)
    }

    @Test
    fun dnsNameCacheNamesLaterConnections() {
        DnsNameCache.record("179.60.195.40", "G.WhatsApp.net.")
        assertEquals("g.whatsapp.net", DnsNameCache.nameFor("179.60.195.40"))
    }

    @Test
    fun secureShareIgnoresUnverifiableTraffic() {
        val statuses = listOf("TLS_1_3", "TLS_1_2", "CLEARTEXT", "UNKNOWN", "UNKNOWN")
        assertEquals(2f / 3f, EncryptionStatus.secureShare(statuses), 0.001f)
        assertEquals(0f, EncryptionStatus.secureShare(listOf("UNKNOWN")), 0f)
    }

    @Test
    fun topologyUsesMeasuredTimesAndRealDecisions() {
        val conns = listOf(
            conn("g.whatsapp.net", connectMs = 140, status = "UNKNOWN"),
            conn("g.whatsapp.net", connectMs = 160, status = "UNKNOWN"),
        )
        val dns = listOf(dns("g.whatsapp.net", responseMs = 31), dns("g.whatsapp.net", responseMs = 29))
        val route = TopologyBuilder.build("com.whatsapp", "WhatsApp", "1.1.1.1 (plain DNS)", conns, dns).single()

        val (app, policy, resolver, server, encryption) = route.hops
        assertEquals("WhatsApp", app.label)
        assertEquals("ALLOWED", policy.badge)                       // nothing was blocked
        assertEquals("31ms", resolver.latencyLabel)                 // median of measured lookups
        assertEquals("160ms", server.latencyLabel)                  // median of measured connects
        assertTrue(server.label.startsWith("Meta"))
        assertEquals(TopologyState.NEUTRAL, encryption.state)       // own protocol: not called cleartext
        assertFalse(encryption.label.contains("Not encrypted"))
    }

    @Test
    fun grantedLocationPlusTrackersIsFlagged() {
        val findings = PermissionMismatchDetector.exposure(
            appName = "Some App",
            grantedPermissions = setOf("android.permission.ACCESS_FINE_LOCATION"),
            destinations = listOf(
                ObservedDestination("graph.facebook.com", "Meta", "Facebook SDK", firstParty = false, bytesSent = 4_000, backgroundConnections = 0),
            ),
            idleDnsQueries = 0,
        )
        assertEquals("granted_access_with_trackers", findings.single().id)
    }

    @Test
    fun noGrantedPermissionsMeansNoExposureFinding() {
        val findings = PermissionMismatchDetector.exposure(
            appName = "Calculator",
            grantedPermissions = emptySet(),
            destinations = listOf(ObservedDestination("t.example", "X", "Tracker", false, 10, 5)),
            idleDnsQueries = 100,
        )
        assertTrue(findings.isEmpty())
    }

    private fun conn(host: String, connectMs: Long, status: String) = ConnectionEntity(
        appUid = 10_001, packageName = "com.whatsapp", destinationIp = "157.240.1.1", destinationPort = 443,
        domain = host, connectMs = connectMs, encryptionStatus = status, bytesSent = 100, bytesReceived = 200,
    )

    private fun dns(host: String, responseMs: Long) = DnsQueryEntity(
        timestamp = 1, appPackage = "com.whatsapp", appName = "WhatsApp", domain = host,
        wasBlocked = false, phoneWasIdle = false, responseMs = responseMs, answerIp = "157.240.1.1",
    )
}
