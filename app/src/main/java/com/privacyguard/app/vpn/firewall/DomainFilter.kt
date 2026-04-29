package com.privacyguard.vpn.firewall

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * High-performance domain blocklist backed by a reverse-label trie.
 *
 * A domain is split into its labels in reverse order (e.g. "ads.example.com"
 * → ["com", "example", "ads"]) and inserted into the trie. Wildcard nodes
 * (represented by "*") match any label at that position.
 *
 * Complexity:
 *  - Insert:  O(L) where L = number of labels
 *  - Lookup:  O(L) — traverses at most L trie levels + 1 wildcard check per level
 *  - Memory:  O(N × L) total nodes for N domains with L labels average
 *
 * Thread safety: the trie is rebuilt atomically via [rebuild]. Concurrent
 * [isBlocked] calls are always lock-free reads.
 *
 * Example blocklist entries:
 *  - "doubleclick.net"   → blocks exact domain
 *  - "*.ads.example.com" → blocks all subdomains
 *  - "*.example.com"     → blocks example.com and all sub-domains
 */
class DomainFilter {

    // ─────────────────────────────────────────────────────────────────────────
    // Trie
    // ─────────────────────────────────────────────────────────────────────────

    private class TrieNode {
        val children = ConcurrentHashMap<String, TrieNode>(4)
        @Volatile var isTerminal = false  // true = domain ends here (match)
    }

    @Volatile private var root = TrieNode()

    // ─────────────────────────────────────────────────────────────────────────
    // Insertions
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Adds [domain] to the blocklist.
     *
     * Patterns:
     *  - "example.com"    → exact domain match
     *  - "*.example.com"  → example.com + all subdomains
     */
    fun addDomain(domain: String) {
        val labels = parseLabels(domain)
        if (labels.isEmpty()) return

        var node = root
        for (label in labels) {
            node = node.children.getOrPut(label) { TrieNode() }
        }
        node.isTerminal = true
        insertedCount.incrementAndGet()
    }

    /**
     * Loads an entire blocklist, replacing the existing one atomically.
     * More efficient than calling [addDomain] in a loop for large lists,
     * because the new trie is built offline and then swapped in.
     *
     * @param domains iterable of domain strings (lines from a hosts file, etc.)
     */
    fun rebuild(domains: Iterable<String>) {
        val newRoot = TrieNode()
        var count   = 0L
        val domainList = mutableListOf<String>()

        for (raw in domains) {
            val domain = raw.trim().lowercase()
            if (domain.isEmpty() || domain.startsWith('#')) continue

            val labels = parseLabels(domain)
            if (labels.isEmpty()) continue

            var node = newRoot
            for (label in labels) {
                node = node.children.getOrPut(label) { TrieNode() }
            }
            node.isTerminal = true
            count++
            domainList.add(domain)
        }

        root          = newRoot   // atomic swap
        insertedCount.set(count)

        // Accelerate lookups with native bloom filter when Rust is available
        if (com.privacyguard.core.native_engine.RustBridge.isAvailable) {
            com.privacyguard.core.native_engine.RustBridge.bloomRebuild(domainList.toTypedArray())
        }
    }

    /**
     * Removes [domain] from the blocklist.
     * Note: does not prune empty nodes — use [rebuild] for a full reset.
     *
     * @return true if the domain was present and removed.
     */
    fun removeDomain(domain: String): Boolean {
        val labels = parseLabels(domain)
        if (labels.isEmpty()) return false

        var node = root
        val path = mutableListOf<Pair<TrieNode, String>>()

        for (label in labels) {
            val child = node.children[label] ?: return false
            path += Pair(node, label)
            node = child
        }

        if (!node.isTerminal) return false
        node.isTerminal = false
        insertedCount.decrementAndGet()
        return true
    }

    /** Removes all domains from the blocklist. */
    fun clear() {
        root = TrieNode()
        insertedCount.set(0)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lookup
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if [domain] is blocked.
     *
     * Matching rules (checked in order):
     *  1. Exact match:    "ads.example.com" matches rule "ads.example.com"
     *  2. Wildcard match: "ads.example.com" matches rule "*.example.com"
     *  3. Parent match:   "sub.ads.example.com" matches rule "ads.example.com"
     *     if [allowSubdomainInheritance] is true (default).
     */
    fun isBlocked(domain: String, allowSubdomainInheritance: Boolean = true): Boolean {
        lookupCount.incrementAndGet()

        val d = domain.lowercase().trimEnd('.')
        if (d.isEmpty()) return false

        // Bloom filter fast-path: if native says no, skip trie entirely (~5x faster)
        if (com.privacyguard.core.native_engine.RustBridge.isAvailable &&
            !com.privacyguard.core.native_engine.RustBridge.bloomCheck(d)) {
            return false
        }

        val labels = d.split('.').reversed()
        if (labels.isEmpty()) return false

        val result = traverseTrie(root, labels, 0, allowSubdomainInheritance)
        if (result) blockedCount.incrementAndGet()
        return result
    }

    private fun traverseTrie(
        node: TrieNode,
        labels: List<String>,
        index: Int,
        allowSubdomainInheritance: Boolean,
    ): Boolean {
        // If this node is a terminal, the domain is blocked
        if (node.isTerminal) return true

        if (index >= labels.size) {
            // FIXED: "*.example.com" also blocks the base domain "example.com".
            return node.children["*"]?.isTerminal == true
        }

        val label = labels[index]

        // Check wildcard child first ("*" matches any label)
        val wildcardChild = node.children["*"]
        if (wildcardChild != null) {
            // "*" can either match one label (continue) or match and terminate
            if (wildcardChild.isTerminal) return true
            if (traverseTrie(wildcardChild, labels, index + 1, allowSubdomainInheritance)) return true
        }

        // Check exact label child
        val exactChild = node.children[label]
        if (exactChild != null) {
            if (traverseTrie(exactChild, labels, index + 1, allowSubdomainInheritance)) return true
        }

        return false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses [domain] into reversed label segments for trie insertion.
     * "*.ads.example.com" → ["com", "example", "ads", "*"]
     */
    private fun parseLabels(domain: String): List<String> {
        val d = domain.lowercase().trimEnd('.')
        if (d.isEmpty()) return emptyList()

        // Convert "*.example.com" to labels that support wildcard at head
        return if (d.startsWith("*.")) {
            val rest = d.substring(2).split('.').reversed()
            rest + "*"
        } else {
            d.split('.').reversed()
        }
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