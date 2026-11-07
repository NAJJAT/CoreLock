package com.privacyguard.vpn.firewall

import java.util.concurrent.atomic.AtomicLong

/**
 * Domain blocklist: a name on the list blocks itself and every subdomain.
 *
 * Example entries:
 *  - "doubleclick.net"   → doubleclick.net and *.doubleclick.net
 *  - "*.example.com"     → example.com and all its subdomains
 *
 * Stored as two sorted arrays of 64-bit name fingerprints ([FingerprintSet], ADR 0014):
 * 8 bytes per name instead of a HashSet entry plus a String (about 105 bytes). A lookup
 * fingerprints the name and each parent in place and binary-searches: no allocation. The
 * names themselves are not kept; the database has them.
 *
 * Thread safety: the arrays are immutable once published; [rebuild], [addDomain] and
 * [removeDomain] swap in new ones, so [isBlocked] is a lock-free read.
 */
class DomainFilter {

    /** [names]: entries as written; [wildcards]: entries written "*.name", stored as "name". */
    private class Lists(names: LongArray, wildcards: LongArray) {
        val names = FingerprintSet(names)
        val wildcards = FingerprintSet(wildcards)
    }

    @Volatile private var lists = Lists(NameFingerprints.EMPTY, NameFingerprints.EMPTY)

    // ─────────────────────────────────────────────────────────────────────────
    // Changes
    // ─────────────────────────────────────────────────────────────────────────

    /** Adds one entry. Copies an array: meant for small changes and tests, not bulk loads. */
    @Synchronized
    fun addDomain(domain: String) {
        val (name, wild) = normalize(domain) ?: return
        val fp = NameFingerprints.of(name)
        val current = lists
        val target = (if (wild) current.wildcards else current.names).sorted
        if (NameFingerprints.contains(target, fp)) return
        val grown = NameFingerprints.sortedUnique(target.copyOf(target.size + 1).also { it[target.size] = fp })
        lists = if (wild) Lists(current.names.sorted, grown) else Lists(grown, current.wildcards.sorted)
        insertedCount.incrementAndGet()
    }

    /**
     * Replaces the whole list atomically: the new arrays are built aside and then
     * swapped in, so lookups never see a half-loaded list.
     */
    fun rebuild(domains: Iterable<String>) {
        var names = LongArray(if (domains is Collection<*>) domains.size else 1_024)
        var wildcards = LongArray(16)
        var n = 0
        var w = 0
        for (raw in domains) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) continue
            val (name, wild) = normalize(line) ?: continue
            val fp = NameFingerprints.of(name)
            if (wild) {
                if (w == wildcards.size) wildcards = wildcards.copyOf(w * 2)
                wildcards[w++] = fp
            } else {
                if (n == names.size) names = names.copyOf(maxOf(16, n * 2))
                names[n++] = fp
            }
        }
        publish(NameFingerprints.sortedUnique(names, n), NameFingerprints.sortedUnique(wildcards, w))
    }

    /** Swaps in arrays that are already sorted and unique (the start-up cache, ADR 0014). */
    internal fun replaceWithFingerprints(names: LongArray, wildcards: LongArray) = publish(names, wildcards)

    /** The current arrays, sorted and unique, for the start-up cache. Not copies: do not change them. */
    internal fun fingerprints(): Pair<LongArray, LongArray> = lists.let { it.names.sorted to it.wildcards.sorted }

    private fun publish(names: LongArray, wildcards: LongArray) {
        synchronized(this) {
            lists = Lists(names, wildcards)
            // Distinct entries: the lists overlap, and counting lines overstated the size.
            insertedCount.set((names.size + wildcards.size).toLong())
        }
    }

    /** @return true if the entry was present and removed. */
    @Synchronized
    fun removeDomain(domain: String): Boolean {
        val (name, wild) = normalize(domain) ?: return false
        val fp = NameFingerprints.of(name)
        val current = lists
        val target = (if (wild) current.wildcards else current.names).sorted
        if (!NameFingerprints.contains(target, fp)) return false
        val shrunk = target.filter { it != fp }.toLongArray()
        lists = if (wild) Lists(current.names.sorted, shrunk) else Lists(shrunk, current.wildcards.sorted)
        insertedCount.decrementAndGet()
        return true
    }

    /** Removes all domains from the blocklist. */
    @Synchronized
    fun clear() {
        lists = Lists(NameFingerprints.EMPTY, NameFingerprints.EMPTY)
        insertedCount.set(0)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lookup
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if [domain] is blocked: it, or a parent of it, is on the list.
     * With [allowSubdomainInheritance] false, a plain entry blocks only itself
     * ("*.name" entries still cover subdomains).
     *
     * Allocation-free: case and trailing dots are handled by index, and each parent is
     * fingerprinted where it stands.
     */
    fun isBlocked(domain: String, allowSubdomainInheritance: Boolean = true): Boolean {
        lookupCount.incrementAndGet()

        var end = domain.length
        while (end > 0 && domain[end - 1] == '.') end--
        if (end == 0) return false

        val current = lists
        var start = 0
        while (true) {
            val fp = NameFingerprints.of(domain, start, end)
            if (current.wildcards.contains(fp)) return hit()
            if ((start == 0 || allowSubdomainInheritance) && current.names.contains(fp)) return hit()
            val dot = domain.indexOf('.', start)
            if (dot < 0 || dot >= end - 1) return false
            start = dot + 1
        }
    }

    private fun hit(): Boolean {
        blockedCount.incrementAndGet()
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * "*.Example.com." → ("Example.com", true); null for an empty entry. Not lower-cased
     * here: the name is only ever fingerprinted, and [NameFingerprints.of] lower-cases char
     * by char, exactly as at lookup (String.lowercase() would turn some characters into
     * two, "İ", and the fingerprints would differ). Copying each of ~500k names through a
     * list of chars on every rebuild only cost time and garbage.
     */
    private fun normalize(domain: String): Pair<String, Boolean>? {
        val d = domain.trim().trimEnd('.')
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

    /** Bytes the lists hold in memory (arrays and directories), for diagnostics and the memory test. */
    val heapBytes: Long get() = lists.let { it.names.bytes + it.wildcards.bytes }

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
