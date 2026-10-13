package com.privacyguard.app.core.network

import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.ui.traffic.EvidenceKind
import com.privacyguard.app.ui.traffic.HomeEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityProbesTest {

    @Test
    fun os_connectivity_checks_are_probes() {
        assertTrue(ConnectivityProbes.isProbe("com.samsung.android.server.wifi.mobilewips", "www.python.org"))
        assertTrue(ConnectivityProbes.isProbe("com.google.android.networkstack", "www.google.com"))
        assertTrue(ConnectivityProbes.isProbe(null, "connectivitycheck.gstatic.com"))
    }

    @Test
    fun ordinary_traffic_to_the_same_hosts_is_not() {
        // An app (or the user) opening www.google.com over HTTP is not a probe.
        assertFalse(ConnectivityProbes.isProbe("com.android.chrome", "www.google.com"))
        assertFalse(ConnectivityProbes.isProbe("com.android.chrome", "neverssl.com"))
    }

    @Test
    fun probes_are_not_counted_as_unencrypted_anywhere() {
        val wips = ConnectionEntity(appUid = 1, packageName = "com.samsung.android.server.wifi.mobilewips",
            domain = "www.kernel.org", destinationIp = "151.101.1.55", encryptionStatus = "CLEARTEXT")
        val check = ConnectionEntity(appUid = 1, packageName = "android", domain = "connectivitycheck.gstatic.com",
            destinationIp = "142.250.179.131", encryptionStatus = "CLEARTEXT")
        val real = ConnectionEntity(appUid = 1, packageName = "com.android.chrome", domain = "neverssl.com",
            destinationIp = "34.223.124.45", encryptionStatus = "CLEARTEXT")
        val list = listOf(wips, check, real)
        assertEquals(listOf("neverssl.com"), HomeEvidence.select(EvidenceKind.CLEARTEXT_24H, list).map { it.domain })
        assertEquals(1, HomeEvidence.encryptionShare(list).cleartext)
    }
}
