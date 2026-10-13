package com.privacyguard.core.filter

import com.privacyguard.app.data.repository.BlocklistRepo
import com.privacyguard.core.filter.FilterEngine.BlockLevel
import com.privacyguard.vpn.firewall.DomainFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionLevelsTest {

    @Test
    fun minimal_blocks_only_malware() {
        assertEquals(setOf(BlocklistRepo.CAT_MALWARE), ProtectionLevels.categoriesFor(BlockLevel.MINIMAL))
        assertFalse(ProtectionLevels.includesCuratedAdHosts(BlockLevel.MINIMAL))
    }

    @Test
    fun standard_adds_ads_and_trackers_but_not_telemetry() {
        val cats = ProtectionLevels.categoriesFor(BlockLevel.STANDARD)
        assertEquals(setOf(BlocklistRepo.CAT_MALWARE, BlocklistRepo.CAT_ADS, BlocklistRepo.CAT_TRACKERS), cats)
        assertTrue(ProtectionLevels.includesCuratedAdHosts(BlockLevel.STANDARD))
    }

    @Test
    fun strict_adds_telemetry() {
        val cats = ProtectionLevels.categoriesFor(BlockLevel.STRICT)
        assertTrue(cats.containsAll(ProtectionLevels.categoriesFor(BlockLevel.STANDARD)))
        assertTrue(BlocklistRepo.CAT_TELEMETRY in cats)
    }

    @Test
    fun each_level_blocks_a_superset_of_the_one_below() {
        val m = ProtectionLevels.categoriesFor(BlockLevel.MINIMAL)
        val s = ProtectionLevels.categoriesFor(BlockLevel.STANDARD)
        val x = ProtectionLevels.categoriesFor(BlockLevel.STRICT)
        assertTrue(s.containsAll(m) && s.size > m.size)
        assertTrue(x.containsAll(s) && x.size > s.size)
    }

    @Test
    fun unknown_or_missing_level_falls_back_to_standard() {
        assertEquals(BlockLevel.STANDARD, ProtectionLevels.parse("bogus"))
        assertEquals(BlockLevel.STANDARD, ProtectionLevels.parse(null))
        assertEquals(BlockLevel.STRICT, ProtectionLevels.parse("STRICT"))
    }

    /** The level changes what the DNS filter actually blocks, end to end. */
    @Test
    fun filter_built_for_a_level_blocks_exactly_its_categories() {
        val byCategory = mapOf(
            BlocklistRepo.CAT_MALWARE to "malware.example",
            BlocklistRepo.CAT_ADS to "ads.example",
            BlocklistRepo.CAT_TRACKERS to "tracker.example",
            BlocklistRepo.CAT_TELEMETRY to "telemetry.example",
        )
        fun filterFor(level: BlockLevel) = DomainFilter().apply {
            rebuild(ProtectionLevels.categoriesFor(level).mapNotNull { byCategory[it] })
        }

        val minimal = filterFor(BlockLevel.MINIMAL)
        assertTrue(minimal.isBlocked("malware.example"))
        assertFalse(minimal.isBlocked("ads.example"))

        val standard = filterFor(BlockLevel.STANDARD)
        assertTrue(standard.isBlocked("ads.example"))
        assertTrue(standard.isBlocked("tracker.example"))
        assertFalse(standard.isBlocked("telemetry.example"))

        assertTrue(filterFor(BlockLevel.STRICT).isBlocked("telemetry.example"))
    }
}
