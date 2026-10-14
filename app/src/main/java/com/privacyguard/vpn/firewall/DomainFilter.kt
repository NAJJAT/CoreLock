package com.privacyguard.vpn.firewall

import java.util.concurrent.atomic.AtomicLong

/**
 * Domain blocklist: a name on the list blocks itself and every subdomain.
 *
 * Example entries:
 *  - "doubleclick.net"   → doubleclick.net and *.doubleclick.net
 *  - "*.example.com"     → example.com and all its subdomains
 *
 * Stored as two hash sets of plain names; a lookup checks the name and each parent
 * (a handful of set lookups). This replaced a trie with one ConcurrentHashMap per
 * label, which needed about 100 MB of heap for the ~220k names of the default lists.
 *
 * Thread safety: the sets are immutable once published; [rebuild], [addDomain] and
 * [removeDomain] swap in new ones, so [isBlocked] is a lock-free read.
 *
 * [useNativeBloom]: the native bloom filter is one global instance, so only one
 * DomainFilter (the main blocklist) may use it; a second one would overwrite it.
 */
class DomainFilter(
    private val useNativeBloom: Boolean = true,
    private val nativeBloom: com.privacyguard.core.native_engine.NativeBloom =
        com.privacyguard.core.native_engine.NativeBloom.Rust,
) {

    /** [names]: entries as written; [wildcards]: entries written "*.name", stored as "name". */
    private class Lists(val names: Set<String>, val wildcards: Set<String>)

    @Volatile private var lists = Lists(emptySet(), emptySet())

    /**
     * The native bloom filter holds every current entry. [addDomain] does not update
     * it, so after one the fast path is skipped until the next [rebuild]: a "no"
     * from a stale filter would let the added name through.
     */
    @Volatile private var bloomComplete = false

    // ─────────────────────────────────────────────────────────────────────────
    // Changes
    // ─────────────────────────────────────────────────────────────────────────

    /** Adds one entry. Copies the sets: meant for small changes and tests, not bulk loads. */
    @Synchronized
    fun addDomain(domain: String) {
        val (name, wild) = normalize(domain) ?: return
        val current = lists
        if (name in (if (wild) current.wildcards else current.names)) return
        bloomComplete = false
        lists = if (wild) Lists(current.names, current.wildcards + name)
        else Lists(current.names + name, current.wildcards)
        insertedCount.incrementAndGet()
    }

    /**
     * Replaces the whole list atomically: the new sets are built aside and then
     * swapped in, so lookups never see a half-loaded list.
     */
    fun rebuild(domains: Iterable<String>) {
        val names = HashSet<String>()
        val wildcards = HashSet<String>()
        val nativeList = if (useNativeBloom) mutableListOf<String>() else null
        for (raw in domains) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) continue
            val (name, wild) = normalize(line) ?: continue
            if (wild) wildcards += name else names += name
            nativeList?.add(if (wild) "*.$name" else name)
        }
        // Accelerate lookups with native bloom filter when Rust is available. Off while
        // the sets and the filter disagree, so no lookup sees a filter older than its list.
        synchronized(this) {
            bloomComplete = false
            lists = Lists(names, wildcards)
            // Distinct entries: the lists overlap, and counting lines overstated the size.
            insertedCount.set((names.size + wildcards.size).toLong())
            if (nativeList != null && nativeBloom.isAvailable) {
                nativeBloom.rebuild(nativeList.toTypedArray())
                bloomComplete = true
            }
        }
    }

    /** @return true if the entry was present and removed. */
    @Synchronized
    fun removeDomain(domain: String): Boolean {
        val (name, wild) = normalize(domain) ?: return false
        val current = lists
        if (name !in (if (wild) current.wildcards else current.names)) return false
        lists = if (wild) Lists(current.names, current.wildcards - name)
        else Lists(current.names - name, current.wildcards)
        insertedCount.decrementAndGet()
        return true
    }

    /** Removes all domains from the blocklist. */
    @Synchronized
    fun clear() {
        lists = Lists(emptySet(), emptySet())
        insertedCount.set(0)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lookup
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if [domain] is blocked: it, or a parent of it, is on the list.
     * With [allowSubdomainInheritance] false, a plain entry blocks only itself
     * ("*.name" entries still cover subdomains).
     */
    fun isBlocked(domain: String, allowSubdomainInheritance: Boolean = true): Boolean {
        lookupCount.incrementAndGet()

        val d = domain.lowercase().trimEnd('.')
        if (d.isEmpty()) return false

        // Bloom filter fast-path: if native says no, skip the sets
        if (useNativeBloom && bloomComplete && nativeBloom.isAvailable && !mightBeInNativeBloom(d)) {
            return false
        }

        val current = lists
        var suffix = d
        while (true) {
            if (suffix in current.wildcards) return hit()
            if (suffix in current.names && (suffix == d || allowSubdomainInheritance)) return hit()
            val dot = suffix.indexOf('.')
            if (dot < 0) return false
            suffix = suffix.substring(dot + 1)
        }
    }

    private fun hit(): Boolean {
        blockedCount.incrementAndGet()
        return true
    }

    /**
     * The name and each parent, as a plain entry or as "*.parent". "*.name" covers
     * name itself too, so it is checked at every level including the first; skipping
     * it there let "*.example.com" miss "example.com" with the native library (R-1).
     */
    private fun mightBeInNativeBloom(domain: String): Boolean {
        var suffix = domain
        while (true) {
            if (nativeBloom.check(suffix) || nativeBloom.check("*.$suffix")) return true
            val dot = suffix.indexOf('.')
            if (dot < 0) return false
            suffix = suffix.substring(dot + 1)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** "*.Example.com." → ("example.com", true); null for an empty entry. */
    private fun normalize(domain: String): Pair<String, Boolean>? {
        val d = domain.trim().lowercase().trimEnd('.')
        if (d.isEmpty() || d == "*") return null
        return if (d.startsWith("*.")) d.substring(2).takeIf { it.isNotEmpty() }?.let { it to true }
        else d to false
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
