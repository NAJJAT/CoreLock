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

    fun analyze(pkg: String?, domain: String): List<Anomaly> {
        totalAnalyzed.incrementAndGet()
        val pkgKey = pkg ?: "unknown"

        if (seenDomains.add(domain)) totalNewDomains.incrementAndGet()

        val anomalies = mutableListOf<Anomaly>()
        val now = System.currentTimeMillis()

        // Record query
        val history = queryHistory.getOrPut(pkgKey) { ArrayDeque() }
        history.addLast(now to domain)
        // Keep last 100 entries
        while (history.size > 100) history.removeFirst()

        // Check for DNS tunneling signatures: very long domain or many subdomains
        val labels = domain.split('.')
        if (labels.size > 5) {
            anomalies += Anomaly(
                timestamp   = now,
                packageName = pkgKey,
                domain      = domain,
                type        = AnomalyType.EXCESSIVE_SUBDOMAINS,
                description = "Unusually many subdomains: ${labels.size}",
                severity    = 6,
            )
        }
        if (domain.length > 50) {
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

        // High frequency: >20 queries in last 10 seconds
        val windowStart = now - 10_000L
        val recentCount = history.count { (ts, _) -> ts > windowStart }
        if (recentCount > 20) {
            anomalies += Anomaly(
                timestamp   = now,
                packageName = pkgKey,
                domain      = domain,
                type        = AnomalyType.HIGH_FREQUENCY,
                description = "$recentCount DNS queries in last 10 seconds",
                severity    = 7,
            )
        }

        val sameDomainCount = history.count { (ts, seenDomain) -> ts > windowStart && seenDomain == domain }
        if (sameDomainCount >= BEACON_QUERY_THRESHOLD) {
            anomalies += Anomaly(
                timestamp   = now,
                packageName = pkgKey,
                domain      = domain,
                type        = AnomalyType.DGA_BEACON,
                description = "$sameDomainCount repeated DNS lookups in 10 seconds",
                severity    = 7,
            )
        }

        detectedAnomalies.addAndGet(anomalies.size.toLong())
        anomalies.forEach { anomalyListener?.onAnomaly(it) }
        return anomalies
    }

    fun seenDomainCount(): Int = seenDomains.size

    companion object {
        const val HIGH_ENTROPY_THRESHOLD = 3.4
        const val MIN_DGA_LABEL_LENGTH = 12
        const val MIN_DGA_DIGIT_RATIO = 0.15f
        const val BEACON_QUERY_THRESHOLD = 8

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
