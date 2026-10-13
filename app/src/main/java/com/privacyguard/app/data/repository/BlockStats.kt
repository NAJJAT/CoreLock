package com.privacyguard.app.data.repository

import com.privacyguard.app.data.db.AppDatabase

/**
 * The one definition of "blocked" that every screen and report uses:
 * DNS lookups answered "not found" by the blocklists or rules, plus connection
 * attempts PrivacyGuard dropped (rules, DNS-bypass guard, cleartext policy).
 * Both are real rows, so each number opens a list of the same size.
 */
class BlockStats(private val db: AppDatabase) {

    data class Counts(
        val blockedLookups: Int,
        val blockedConnections: Int,
        /** All DNS lookups in the window, blocked or not. */
        val allLookups: Int,
        /** Distinct names and addresses behind [total]: "44 blocked · 4 domains". */
        val domains: Int = 0,
    ) {
        val total: Int get() = blockedLookups + blockedConnections

        /**
         * Share of DNS lookups that were blocked. Lookups are the unit blocking works
         * on; mixing in connections would count most visits twice (lookup + connection).
         */
        val lookupBlockRate: Float get() =
            if (allLookups == 0) 0f else (blockedLookups.toFloat() / allLookups).coerceIn(0f, 1f)
    }

    suspend fun between(from: Long, to: Long): Counts = Counts(
        blockedLookups = db.dnsQueryDao().countBlockedBetween(from, to),
        blockedConnections = db.connectionDao().countBlockedBetween(from, to),
        allLookups = db.dnsQueryDao().countBetween(from, to),
        domains = (db.dnsQueryDao().blockedDomainsBetween(from, to) + db.connectionDao().blockedHostsBetween(from, to))
            .map { it.lowercase().trimEnd('.') }.toSet().size,
    )
}
