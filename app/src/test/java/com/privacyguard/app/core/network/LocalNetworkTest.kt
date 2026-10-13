package com.privacyguard.app.core.network

import com.privacyguard.app.core.privacy.PrivacyScoreCalculator
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.ui.traffic.EvidenceKind
import com.privacyguard.app.ui.traffic.HomeEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkTest {

    @Test
    fun private_link_local_loopback_and_multicast_are_local() {
        listOf("192.168.0.106", "10.1.2.3", "172.16.0.1", "172.31.255.254", "169.254.1.1", "127.0.0.1",
            "224.0.0.251", "239.255.255.250", "255.255.255.255",
            "fe80::1", "fd00:1:fd00:1::2", "ff02::fb", "::1").forEach {
            assertTrue(it, LocalNetwork.isLocalAddress(it))
        }
    }

    @Test
    fun internet_addresses_are_not_local() {
        listOf("8.8.8.8", "172.32.0.1", "192.169.0.1", "100.64.0.1", "2a00:1450:400c:c0a::5b", null, "").forEach {
            assertFalse("$it", LocalNetwork.isLocalAddress(it))
        }
    }

    @Test
    fun dot_local_names_are_local() {
        assertTrue(LocalNetwork.isLocalHost("Living-Room-TV.local."))
        assertFalse(LocalNetwork.isLocalHost("local.example.com"))
    }

    private fun conn(ip: String, enc: String) = ConnectionEntity(appUid = 1, destinationIp = ip, encryptionStatus = enc)

    @Test
    fun plain_http_to_a_chromecast_is_not_unencrypted_internet_traffic() {
        val list = listOf(conn("192.168.0.106", "CLEARTEXT"), conn("93.184.215.14", "CLEARTEXT"), conn("1.1.1.1", "TLS_1_3"))
        // Home's cleartext card and its list
        assertEquals(listOf("93.184.215.14"), HomeEvidence.select(EvidenceKind.CLEARTEXT_24H, list).map { it.destinationIp })
        // Encryption share and score leave the local row out entirely
        val share = HomeEvidence.encryptionShare(list)
        assertEquals(1, share.cleartext)
        assertEquals(1, share.local)
        assertEquals(1, PrivacyScoreCalculator.calculate(list, emptyList(), 0).cleartextConnections)
    }
}
