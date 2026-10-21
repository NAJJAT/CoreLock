package com.privacyguard.core.filter

import com.privacyguard.app.data.repository.BlocklistRepo

/**
 * What each protection level blocks at DNS. Blocklist domains are stored under one
 * category each (see BlocklistManager.sourceMetadata and its built-in seed):
 *
 *  - MINIMAL:  malware and phishing hosts only. Ads and trackers load normally.
 *  - STANDARD: MINIMAL + ads (StevenBlack, EasyList, OISD, Hagezi, built-in ad hosts)
 *              + trackers (EasyPrivacy, built-in analytics/attribution hosts).
 *  - STRICT:   STANDARD + telemetry: OS and vendor diagnostics (Microsoft, Apple,
 *              Amazon, Mozilla, Dropbox, Google Safe Browsing). This can break
 *              features that depend on them, which is why it is not the default.
 *
 * Always applied, whatever the level: the user's own rules, and the Ad-SDK policy
 * when it is switched on. A category or source the user switched off in the
 * blocklist settings stays off at every level.
 */
object ProtectionLevels {

    fun categoriesFor(level: FilterEngine.BlockLevel): Set<String> = when (level) {
        FilterEngine.BlockLevel.MINIMAL -> setOf(BlocklistRepo.CAT_MALWARE)
        FilterEngine.BlockLevel.STANDARD -> setOf(
            BlocklistRepo.CAT_MALWARE, BlocklistRepo.CAT_ADS, BlocklistRepo.CAT_TRACKERS,
        )
        FilterEngine.BlockLevel.STRICT -> setOf(
            BlocklistRepo.CAT_MALWARE, BlocklistRepo.CAT_ADS, BlocklistRepo.CAT_TRACKERS,
            BlocklistRepo.CAT_TELEMETRY,
        )
    }

    /**
     * Ad lists that belong to another level: Standard uses HaGeZi Pro mini, Strict the
     * full HaGeZi Pro (which contains Pro mini) and OISD small.
     */
    fun excludedSourcesFor(level: FilterEngine.BlockLevel): Set<String> = when (level) {
        FilterEngine.BlockLevel.MINIMAL -> emptySet()
        FilterEngine.BlockLevel.STANDARD -> setOf(BlocklistRepo.SOURCE_HAGEZI_PRO, BlocklistRepo.SOURCE_OISD)
        FilterEngine.BlockLevel.STRICT -> setOf(BlocklistRepo.SOURCE_HAGEZI_PRO_MINI)
    }

    /** Hand-curated ad/tracker hosts (not in the database) belong to ads and trackers. */
    fun includesCuratedAdHosts(level: FilterEngine.BlockLevel): Boolean =
        level >= FilterEngine.BlockLevel.STANDARD

    fun parse(level: String?): FilterEngine.BlockLevel =
        runCatching { FilterEngine.BlockLevel.valueOf(level ?: "") }.getOrDefault(FilterEngine.BlockLevel.STANDARD)
}
