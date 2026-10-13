package com.privacyguard.app.core.geoip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Uses the real asset, so a broken or stale build of it fails here. */
class RegistryTableTest {

    companion object {
        @BeforeClass @JvmStatic
        fun load() = File("src/main/assets/geoip_v4.bin").inputStream().use { RegistryTable.loadFrom(it) }

        private fun ip(s: String) = s.split('.').fold(0L) { acc, p -> (acc shl 8) or p.toLong() }
    }

    @Test
    fun well_known_blocks_have_their_registration_country() {
        assertEquals("NL", RegistryTable.countryOf(ip("193.0.6.139")))   // RIPE NCC, Amsterdam
        assertEquals("JP", RegistryTable.countryOf(ip("133.0.0.1")))     // Japanese academic block
        assertEquals("BR", RegistryTable.countryOf(ip("200.160.0.8")))   // NIC.br
    }

    @Test
    fun private_and_reserved_addresses_have_no_country() {
        assertNull(RegistryTable.countryOf(ip("10.0.0.1")))
        assertNull(RegistryTable.countryOf(ip("192.168.1.1")))
        assertNull(RegistryTable.countryOf(ip("127.0.0.1")))
    }

    @Test
    fun the_table_covers_most_of_the_internet() {
        val probes = listOf("2.16.0.1", "31.13.64.1", "41.0.0.1", "52.95.110.1", "101.0.0.1", "151.101.1.1", "177.0.0.1", "203.0.113.1")
        assertTrue(probes.count { RegistryTable.countryOf(ip(it)) != null } >= 6)
    }
}
