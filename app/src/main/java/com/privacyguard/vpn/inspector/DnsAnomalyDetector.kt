package com.privacyguard.vpn.inspector

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * DNS behavioral anomaly detector.
 *
 * Analyzes DNS queries for signs of:
 *  - DNS tunneling
 *  - DGA (Domain Generation Algorithm) beaconing
 *  - Unusual query volumes
 *
 * No payload decryption — analysis is on DNS query names only.
 */
class DnsAnomalyDetector {

    enum class AnomalyType {
        DNS_TUNNELING,
        DGA_BEACON,
        HIGH_ENTROPY,
        HIGH_FREQUENCY,
        EXCESSIVE_SUBDOMAINS,
        LONG_LABEL,
        // Signs about the network itself (NetworkSignals); stored with its network key.
        DNS_TAMPERED,
        DNS_HIJACK,
        TLS_INTERCEPTION,
        // Server names (TLS/QUIC SNI) an app sent: see core/tls/SniCheck.
        INVALID_SNI,
        SNI_MISMATCH,
    }

    data class Anomaly(
        val timestamp:   Long,
        val packageName: String,
        val domain:      String,
        val type:        AnomalyType,
        val description: String,
        val severity:    Int,
    )

    fun interface AnomalyListener {
        fun onAnomaly(anomaly: Anomaly)
    }

    @Volatile var anomalyListener: AnomalyListener? = null

    val totalAnalyzed     = AtomicLong(0)
    val totalNewDomains   = AtomicLong(0)
    val detectedAnomalies = AtomicLong(0)

    // Per-package query tracking: pkg -> list of (timestamp, domain)
    private val queryHistory = ConcurrentHashMap<String, ArrayDeque<Pair<Long, String>>>(32)
    private val seenDomains  = ConcurrentHashMap.newKeySet<String>()
    // "pkg|domain|type" -> when it was last reported (see REPORT_COOLDOWN_MS).
    private val lastReported = ConcurrentHashMap<String, Long>()

    fun analyze(pkg: String?, domain: String): List<Anomaly> {
        // PrivacyGuard's own lookups (CT checks, blocklist downloads, DoH) are not app behaviour.
        if (isOwnHost(domain) || (pkg != null && pkg.startsWith(OWN_PACKAGE_PREFIX))) return emptyList()
        totalAnalyzed.incrementAndGet()
        // Same "Unknown" as unattributed DNS rows, so the UI labels it "Unknown app".
        val pkgKey = pkg?.takeIf { it.isNotBlank() } ?: "Unknown"

        if (seenDomains.add(domain)) totalNewDomains.incrementAndGet()

        val anomalies = mutableListOf<Anomaly>()
        val now = System.currentTimeMillis()

        // Record the lookup. Android asks for A and AAAA (and HTTPS) records of one name
        // at once; those packets are one lookup, not two or three.
        val history = queryHistory.getOrPut(pkgKey) { ArrayDeque() }
        val sameLookup = history.lastOrNull { it.second == domain }?.let { now - it.first < SAME_LOOKUP_MS } == true
        if (sameLookup) return emptyList()
        history.addLast(now to domain)
        // Keep last 100 entries
        while (history.size > 100) history.removeFirst()

        // Check for DNS tunneling signatures: very long domain or many subdomains.
        // CDNs build long, nested, random names on purpose; the name shape says nothing there.
        val cdn = isKnownCdn(domain)
        val labels = domain.split('.')
        if (!cdn && labels.size > 5) {
            anomalies += Anomaly(
                timestamp   = now,
                packageName = pkgKey,
                domain      = domain,
                type        = AnomalyType.EXCESSIVE_SUBDOMAINS,
                description = "Unusually many subdomains: ${labels.size}",
                severity    = 6,
            )
        }
        if (!cdn && domain.length > 50) {
            anomalies += Anomaly(
                timestamp   = now,
                packageName = pkgKey,
                domain      = domain,
                type        = AnomalyType.LONG_LABEL,
                description = "Unusually long domain: ${domain.length} chars",
                severity    = 5,
            )
        }

        val registrableLabel = domain.substringBefore('.')
        val entropy = shannonEntropy(registrableLabel)
        val digitRatio = registrableLabel.count { it.isDigit() }.toFloat() / registrableLabel.length.coerceAtLeast(1)
        if (
            !isKnownCdn(domain) &&
            registrableLabel.length >= MIN_DGA_LABEL_LENGTH &&
            entropy >= HIGH_ENTROPY_THRESHOLD &&
            digitRatio >= MIN_DGA_DIGIT_RATIO
        ) {
            anomalies += Anomaly(
                timestamp   = now,
                packageName = pkgKey,
                domain      = domain,
                type        = AnomalyType.HIGH_ENTROPY,
                description = "High entropy DNS label: ${"%.2f".format(entropy)} bits/char",
                severity    = 8,
            )
        }

        // Burst: many different names in 10 seconds. Counting distinct names (not
        // packets) keeps one page load with A + AAAA from looking like twice the work.
        val windowStart = now - 10_000L
        val recentCount = history.filter { (ts, _) -> ts > windowStart }.map { it.second }.distinct().size
        if (recentCount > BURST_DISTINCT_NAMES) {
            anomalies += Anomaly(
                timestamp   = now,
                packageName = pkgKey,
                domain      = domain,
                type        = AnomalyType.HIGH_FREQUENCY,
                description = "$recentCount different names looked up in 10 seconds",
                // Medium: browsers and feeds do this on every page; it is context, not a threat.
                severity    = 4,
            )
        }

        val sameDomainCount = history.count { (ts, seenDomain) -> ts > windowStart && seenDomain == domain }
        if (sameDomainCount >= BEACON_QUERY_THRESHOLD) {
            anomalies += Anomaly(
                timestamp   = now,
                packageName = pkgKey,
                domain      = domain,
                type        = AnomalyType.DGA_BEACON,
                description = "$sameDomainCount lookups of the same domain in 10 seconds (possible beaconing)",
                // Medium: apps retry and refresh lookups for many benign reasons.
                severity    = 5,
            )
        }

        // A pattern keeps matching on every further lookup, so report each (app, host)
        // once per cooldown, whatever the type. Name-shape findings use the base domain
        // instead: their hosts are unique by design (UUID labels under fbcdn.net), so per
        // host they never repeat. A burst is about the app, not the host that tipped it:
        // once per app, or one news page adds an alert for each of its dozens of ad hosts.
        // The database enforces the same rules across restarts.
        val fresh = anomalies.sortedByDescending { it.severity }.distinctBy { it.domain }.filter { anomaly ->
            val key = "$pkgKey|${dedupeKey(anomaly)}"
            val last = lastReported[key]
            if (last != null && now - last < REPORT_COOLDOWN_MS) false
            else { lastReported[key] = now; true }
        }
        if (lastReported.size > MAX_TRACKED_REPORTS) {
            lastReported.entries.removeIf { now - it.value >= REPORT_COOLDOWN_MS }
        }

        detectedAnomalies.addAndGet(fresh.size.toLong())
        fresh.forEach { anomalyListener?.onAnomaly(it) }
        return fresh
    }

    private fun dedupeKey(a: Anomaly): String =
        if (dedupesPerApp(a.type)) "type:${a.type.name}" else baseForDedupe(a) ?: a.domain

    /** CDN and cloud hostnames are random-looking by design, not generated by malware. */
    private fun isKnownCdn(domain: String): Boolean =
        CDN_SUFFIXES.any { domain == it || domain.endsWith(".$it") }

    fun seenDomainCount(): Int = seenDomains.size

    companion object {
        const val HIGH_ENTROPY_THRESHOLD = 3.4
        const val MIN_DGA_LABEL_LENGTH = 12
        const val MIN_DGA_DIGIT_RATIO = 0.15f
        // Lookups (A + AAAA count once) of the same name in 10 seconds.
        const val BEACON_QUERY_THRESHOLD = 8
        // Different names in 10 seconds that count as a burst.
        const val BURST_DISTINCT_NAMES = 30
        // Packets for the same name closer than this are one lookup (A, AAAA, HTTPS).
        const val SAME_LOOKUP_MS = 1_000L
        // One alert per app and host per day (per app for a burst).
        const val REPORT_COOLDOWN_MS = 24L * 60L * 60_000L
        private const val OWN_PACKAGE_PREFIX = "com.privacyguard.app"
        /** Hosts PrivacyGuard itself contacts; see CtMonitor, BlocklistSource, DnsHandler, ExodusUpdater. */
        private val OWN_HOSTS = listOf(
            "crt.sh", "oisd.nl", "raw.githubusercontent.com", "cdn.jsdelivr.net",
            "cloudflare-dns.com", "dns.google", "dns.quad9.net",
            "reports.exodus-privacy.eu.org", "api.ipify.org",
        )

        fun isOwnHost(domain: String): Boolean {
            val d = domain.lowercase().trimEnd('.')
            return OWN_HOSTS.any { d == it || d.endsWith(".$it") }
        }
        private const val MAX_TRACKED_REPORTS = 5_000
        private val CDN_SUFFIXES = listOf(
            "cloudfront.net", "akamaiedge.net", "akamaihd.net", "akamai.net", "edgekey.net",
            "fastly.net", "azureedge.net", "azurefd.net", "cloudflare.net", "amazonaws.com",
            "googleusercontent.com", "googlevideo.com", "gvt1.com", "gvt2.com", "1e100.net",
            "fbcdn.net", "cdninstagram.com", "whatsapp.net", "llnwd.net", "b-cdn.net",
            "tiktokcdn.com", "tiktokcdn-eu.com", "tiktokcdn-us.com", "ibyteimg.com", "byteoversea.com",
        )

        /** Findings about how a name looks (length, nesting, randomness). */
        val NAME_SHAPE_TYPES = setOf(AnomalyType.EXCESSIVE_SUBDOMAINS, AnomalyType.LONG_LABEL, AnomalyType.HIGH_ENTROPY)

        /** Findings reported once per app per day, whichever host they were seen on. */
        fun dedupesPerApp(type: AnomalyType): Boolean = type == AnomalyType.HIGH_FREQUENCY

        /** Base domain that name-shape findings are de-duplicated on; null for other types. */
        fun baseForDedupe(a: Anomaly): String? =
            if (a.type in NAME_SHAPE_TYPES) com.privacyguard.app.core.tracker.DomainClassifier.baseDomain(a.domain) else null

        fun shannonEntropy(value: String): Double {
            if (value.isBlank()) return 0.0
            val frequencies = value.groupingBy { it }.eachCount()
            return frequencies.values.sumOf { count ->
                val p = count.toDouble() / value.length.toDouble()
                -p * kotlin.math.log2(p)
            }
        }
    }
}
