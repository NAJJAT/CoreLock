package com.privacyguard.app.ui.ads

import com.privacyguard.app.data.repository.BlocklistRepo
import com.privacyguard.core.filter.FilterEngine.BlockLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Ads screen says "On" only for lists the current level uses (UX audit: "ON" for Strict-only lists at Standard). */
class AdsLevelUseTest {

    @Test
    fun strict_only_lists_are_not_used_at_standard() {
        assertFalse(AdsLevelUse.sourceUsed(BlocklistRepo.SOURCE_HAGEZI_PRO, listOf(BlocklistRepo.CAT_ADS), BlockLevel.STANDARD))
        assertFalse(AdsLevelUse.sourceUsed(BlocklistRepo.SOURCE_OISD, listOf(BlocklistRepo.CAT_ADS), BlockLevel.STANDARD))
        assertTrue(AdsLevelUse.sourceUsed(BlocklistRepo.SOURCE_HAGEZI_PRO_MINI, listOf(BlocklistRepo.CAT_ADS), BlockLevel.STANDARD))
        assertFalse("telemetry is Strict only", AdsLevelUse.categoryUsed(BlocklistRepo.CAT_TELEMETRY, BlockLevel.STANDARD))
    }

    @Test
    fun strict_swaps_pro_mini_for_pro() {
        assertTrue(AdsLevelUse.sourceUsed(BlocklistRepo.SOURCE_HAGEZI_PRO, listOf(BlocklistRepo.CAT_ADS), BlockLevel.STRICT))
        assertFalse(AdsLevelUse.sourceUsed(BlocklistRepo.SOURCE_HAGEZI_PRO_MINI, listOf(BlocklistRepo.CAT_ADS), BlockLevel.STRICT))
    }

    @Test
    fun minimal_uses_only_malware_and_the_dns_bypass_lists() {
        assertTrue(AdsLevelUse.sourceUsed(BlocklistRepo.SOURCE_HAGEZI_TIF_MINI, listOf(BlocklistRepo.CAT_MALWARE), BlockLevel.MINIMAL))
        assertFalse(AdsLevelUse.sourceUsed(BlocklistRepo.SOURCE_HAGEZI_PRO_MINI, listOf(BlocklistRepo.CAT_ADS), BlockLevel.MINIMAL))
        // Layer 2 works at every level.
        assertTrue(AdsLevelUse.sourceUsed(BlocklistRepo.SOURCE_HAGEZI_DOH, listOf(BlocklistRepo.CAT_DOH), BlockLevel.MINIMAL))
        assertTrue(AdsLevelUse.sourceUsed(BlocklistRepo.SOURCE_HAGEZI_DOH_IPS, listOf(BlocklistRepo.CAT_DOH_IP), BlockLevel.MINIMAL))
        // The built-in seed fills several categories; malware is used everywhere.
        assertTrue(AdsLevelUse.sourceUsed(BlocklistRepo.SOURCE_BUILTIN, listOf(BlocklistRepo.CAT_ADS, BlocklistRepo.CAT_MALWARE), BlockLevel.MINIMAL))
    }
}
