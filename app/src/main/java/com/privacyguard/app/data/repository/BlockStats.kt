package com.privacyguard.app.data.repository

import com.privacyguard.app.data.db.AppDatabase

/**
 * The one definition of "blocked" that every screen and report uses:
 * DNS lookups answered "not found" by the blocklists or rules, plus connections
 * PrivacyGuard dropped (rules, DNS-bypass guard, cleartext policy, SNI). Both are
 * history rows, so each number opens a list of the same size. Rows, not attempts:
 * retries are merged (a lookup repeated less than 10 s after the last adds no row;
 * one blocked connection row per app, host, port, protocol and source per minute,
 * BlockRowGate), and
 * ev_blocked_24h_def says so to the user.
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

    /**
     * "Blocked today": since local midnight. The one count the notification, Home, Apps,
     * Activity and Statistics show (owner, 2026-11-04), so the same number never differs
     * between two places; BlockCountAgreementTest checks they all use it.
     */
    suspend fun today(now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Int =
        totalBetween(startOfDay(now, zone), now)

    /** Per app, for the same window as [today]: the Apps list's "N blocked today". */
    suspend fun perAppToday(now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Map<String, Int> =
        perAppBetween(startOfDay(now, zone), now)

    /** [today] with the names behind it ("5 blocked · 2 domains"), same rows and window. */
    suspend fun todayCounts(now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Counts =
        between(startOfDay(now, zone), now)

    companion object {
        /** Local midnight before [now]: where "today" starts. */
        fun startOfDay(now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long =
            java.util.Calendar.getInstance(zone).apply {
                timeInMillis = now
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
    }

    /** [Counts.total] alone (two counts, no name list). */
    suspend fun totalBetween(from: Long, to: Long): Int =
        db.dnsQueryDao().countBlockedBetween(from, to) + db.connectionDao().countBlockedBetween(from, to)

    /**
     * [Counts.total] per app, from the same rows: the Apps list's "N blocked today" adds
     * up to Home's number (apart from rows no app is known for).
     */
    suspend fun perAppBetween(from: Long, to: Long): Map<String, Int> {
        val out = HashMap<String, Int>()
        (db.dnsQueryDao().countBlockedByPackageBetween(from, to) + db.connectionDao().countBlockedByPackageBetween(from, to))
            .forEach { out.merge(it.packageName, it.count, Int::plus) }
        return out
    }

    suspend fun between(from: Long, to: Long): Counts = Counts(
        blockedLookups = db.dnsQueryDao().countBlockedBetween(from, to),
        blockedConnections = db.connectionDao().countBlockedBetween(from, to),
        allLookups = db.dnsQueryDao().countBetween(from, to),
        domains = (db.dnsQueryDao().blockedDomainsBetween(from, to) + db.connectionDao().blockedHostsBetween(from, to))
            .map { it.lowercase().trimEnd('.') }.toSet().size,
    )
}
