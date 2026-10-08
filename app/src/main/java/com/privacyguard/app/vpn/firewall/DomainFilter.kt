package com.privacyguard.vpn.firewall

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Domain blocklist: a set of blocked names, matched against the queried name and
 * each of its parents ("x.ads.example.com" → "ads.example.com" → "example.com" →
 * "com"), so a rule blocks a domain and all its subdomains. "*.example.com" means
 * the same as "example.com" and is stored that way.
 *
 * This replaced a reverse-label trie whose every node was a ConcurrentHashMap:
 * ~400k nodes for 158k domains cost several times the memory of the names
 * themselves, for the same O(labels) lookups.
 *
 * Thread safety: [rebuild] builds a new set and swaps it in; lookups never lock.
 */
class DomainFilter {

    @Volatile private var domains: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Adds [domain] to the blocklist ("example.com" or "*.example.com", which are
     * equivalent: both block the domain and every subdomain).
     */
    fun addDomain(domain: String) {
        val d = normalize(domain) ?: return
        if (domains.add(d)) insertedCount.incrementAndGet()
    }

    /**
     * Loads an entire blocklist, replacing the existing one atomically.
     *
     * @param domains iterable of domain strings (lines from a hosts file, etc.)
     */
    fun rebuild(domains: Iterable<String>) {
        val newSet: MutableSet<String> = ConcurrentHashMap.newKeySet()
        for (raw in domains) {
            if (raw.trimStart().startsWith('#')) continue
            normalize(raw)?.let { newSet.add(it) }
        }
        this.domains = newSet   // atomic swap
        insertedCount.set(newSet.size.toLong())

        // Accelerate lookups with native bloom filter when Rust is available
        if (com.privacyguard.core.native_engine.RustBridge.isAvailable) {
            com.privacyguard.core.native_engine.RustBridge.bloomRebuild(newSet.toTypedArray())
        }
    }

    /**
     * Removes [domain] from the blocklist.
     *
     * @return true if the domain was present and removed.
     */
    fun removeDomain(domain: String): Boolean {
        val d = normalize(domain) ?: return false
        val removed = domains.remove(d)
        if (removed) insertedCount.decrementAndGet()
        return removed
    }

    /** Removes all domains from the blocklist. */
    fun clear() {
        domains = ConcurrentHashMap.newKeySet()
        insertedCount.set(0)
    }

    /**
     * Returns true if [domain] or any parent domain is on the blocklist.
     * [allowSubdomainInheritance] is kept for API compatibility; parents always
     * match, as they did with the trie.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isBlocked(domain: String, allowSubdomainInheritance: Boolean = true): Boolean {
        lookupCount.incrementAndGet()

        val d = domain.lowercase().trimEnd('.')
        if (d.isEmpty()) return false

        // Bloom filter fast-path: if native says no, skip the set entirely.
        if (com.privacyguard.core.native_engine.RustBridge.isAvailable &&
            !mightBeInNativeBloom(d)) {
            return false
        }

        val set = domains
        var candidate = d
        while (true) {
            if (candidate in set) {
                blockedCount.incrementAndGet()
                return true
            }
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    private fun mightBeInNativeBloom(domain: String): Boolean {
        var candidate = domain
        while (true) {
            if (com.privacyguard.core.native_engine.RustBridge.bloomCheck(candidate)) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    /** "*.Example.com." → "example.com"; null for blank input. */
    private fun normalize(domain: String): String? {
        val d = domain.trim().lowercase().trimEnd('.').removePrefix("*.")
        return d.ifEmpty { null }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Statistics
    // ─────────────────────────────────────────────────────────────────────────

    val insertedCount = AtomicLong(0)
    val lookupCount   = AtomicLong(0)
    val blockedCount  = AtomicLong(0)

    /** Number of domains in the blocklist. */
    val size: Long get() = insertedCount.get()

    data class Stats(
        val loadedDomains: Long,
        val totalLookups:  Long,
        val blockedHits:   Long,
    )

    fun stats(): Stats = Stats(
        loadedDomains = insertedCount.get(),
        totalLookups  = lookupCount.get(),
        blockedHits   = blockedCount.get(),
    )
}
