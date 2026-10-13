package com.privacyguard.vpn.firewall

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Blocks IPv4 connections by exact address or CIDR range.
 *
 * Rules are stored as parsed [IpRange] objects.  Lookups iterate the list
 * (O(N)) which is efficient for typical user-created rule sets (< 1,000 entries).
 * For very large IP blocklists (>10,000 CIDRs) a prefix-tree / bitmap structure
 * would be faster, but that is deferred to a future version.
 *
 * Thread safety: [CopyOnWriteArrayList] — writes are infrequent, reads are hot.
 */
class IpFilter {

    // ─────────────────────────────────────────────────────────────────────────
    // IP Range Model
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Represents a single IPv4 CIDR range.
     * Both exact IPs ("1.2.3.4") and ranges ("10.0.0.0/8") are supported.
     */
    data class IpRange(
        /** Original string representation as entered by the user. */
        val cidr: String,
        /** Human-readable label shown in the UI. */
        val label: String = cidr,
    ) {
        private val networkInt: Int
        private val maskInt:    Int

        init {
            if (cidr.contains('/')) {
                val (addr, prefix) = cidr.split('/')
                networkInt = ipToInt(addr)
                val prefixLen = prefix.toInt().coerceIn(0, 32)
                maskInt = if (prefixLen == 0) 0 else (-1 shl (32 - prefixLen))
            } else {
                networkInt = ipToInt(cidr)
                maskInt    = -1   // exact match
            }
        }

        fun matches(ip: String): Boolean {
            val ipInt = ipToInt(ip)
            return (ipInt and maskInt) == (networkInt and maskInt)
        }

        private fun ipToInt(ip: String): Int {
            val parts = ip.split('.')
            if (parts.size != 4) return 0
            return parts.fold(0) { acc, s -> (acc shl 8) or (s.toIntOrNull() ?: 0) }
        }

        override fun toString(): String = "IpRange($cidr)"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Storage
    // ─────────────────────────────────────────────────────────────────────────

    private val blocklist  = CopyOnWriteArrayList<IpRange>()
    private val allowlist  = CopyOnWriteArrayList<IpRange>()   // overrides blocklist

    // ─────────────────────────────────────────────────────────────────────────
    // Mutation
    // ─────────────────────────────────────────────────────────────────────────

    /** Adds a CIDR range to the blocklist. */
    fun blockIp(cidr: String, label: String = cidr) {
        runCatching { blocklist.add(IpRange(cidr, label)) }
    }

    /** Adds a CIDR range to the allowlist (whitelist overrides blocklist). */
    fun allowIp(cidr: String, label: String = cidr) {
        runCatching { allowlist.add(IpRange(cidr, label)) }
    }

    /** Removes all blocked ranges matching [cidr]. */
    fun unblockIp(cidr: String) { blocklist.removeIf { it.cidr == cidr } }

    /** Removes all allowed ranges matching [cidr]. */
    fun unallowIp(cidr: String) { allowlist.removeIf { it.cidr == cidr } }

    /** Replaces the entire blocklist. */
    fun setBlocklist(ranges: List<String>) {
        blocklist.clear()
        ranges.forEach { runCatching { blocklist.add(IpRange(it)) } }
    }

    /** Clears all blocked and allowed ranges. */
    fun clear() {
        blocklist.clear()
        allowlist.clear()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lookup
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if [ip] should be blocked.
     *
     * Logic:
     *  1. If [ip] matches any allowlist range → ALLOW (return false).
     *  2. If [ip] matches any blocklist range → BLOCK (return true).
     *  3. Otherwise → ALLOW (return false).
     */
    fun isBlocked(ip: String): Boolean {
        lookupCount.incrementAndGet()

        // Allowlist takes priority
        if (allowlist.any { it.matches(ip) }) return false

        return if (blocklist.any { it.matches(ip) }) {
            blockedCount.incrementAndGet()
            true
        } else {
            false
        }
    }

    /**
     * Returns the first [IpRange] in the blocklist that matches [ip], or null.
     */
    fun matchingRule(ip: String): IpRange? = blocklist.firstOrNull { it.matches(ip) }

    // ─────────────────────────────────────────────────────────────────────────
    // Statistics
    // ─────────────────────────────────────────────────────────────────────────

    val lookupCount  = AtomicLong(0)
    val blockedCount = AtomicLong(0)

    /** Number of blocked CIDR ranges. */
    val blocklistSize: Int get() = blocklist.size

    /** Number of allowed CIDR ranges. */
    val allowlistSize: Int get() = allowlist.size

    /** All blocked ranges. */
    val allBlockedRanges: List<IpRange> get() = blocklist.toList()

    data class Stats(
        val blocklistEntries: Int,
        val allowlistEntries: Int,
        val totalLookups:     Long,
        val blockedHits:      Long,
    )

    fun stats(): Stats = Stats(
        blocklistEntries = blocklist.size,
        allowlistEntries = allowlist.size,
        totalLookups     = lookupCount.get(),
        blockedHits      = blockedCount.get(),
    )
}