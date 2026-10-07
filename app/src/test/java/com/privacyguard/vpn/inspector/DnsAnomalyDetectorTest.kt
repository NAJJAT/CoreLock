package com.privacyguard.vpn.inspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsAnomalyDetectorTest {

    @Test
    fun entropyForReadableDomainIsLowerThanGeneratedLabel() {
        val readable = DnsAnomalyDetector.shannonEntropy("google")
        val generated = DnsAnomalyDetector.shannonEntropy("x9q2mz8v4pr7ka")

        assertTrue(generated > readable)
        assertTrue(generated >= DnsAnomalyDetector.HIGH_ENTROPY_THRESHOLD)
    }

    @Test
    fun highEntropyDomainCreatesAnomaly() {
        val detector = DnsAnomalyDetector()

        val anomalies = detector.analyze("com.test.app", "x9q2mz8v4pr7ka.example.com")

        assertTrue(anomalies.any { it.type == DnsAnomalyDetector.AnomalyType.HIGH_ENTROPY })
    }

    @Test
    fun repeatedDnsLookupsCreateBeaconAnomaly() {
        val detector = DnsAnomalyDetector()
        var latest = emptyList<DnsAnomalyDetector.Anomaly>()

        repeat(DnsAnomalyDetector.BEACON_QUERY_THRESHOLD) {
            latest = detector.analyze("com.test.app", "c2.example.com")
        }

        assertTrue(latest.any { it.type == DnsAnomalyDetector.AnomalyType.DGA_BEACON })
    }

    @Test
    fun normalDomainDoesNotTriggerEntropyAnomaly() {
        val detector = DnsAnomalyDetector()

        val anomalies = detector.analyze("com.browser", "www.microsoft.com")

        assertEquals(false, anomalies.any { it.type == DnsAnomalyDetector.AnomalyType.HIGH_ENTROPY })
    }

    @Test
    fun sameAnomalyIsReportedOncePerCooldown() {
        val detector = DnsAnomalyDetector()

        // An A and an AAAA lookup for the same name used to produce two identical alerts.
        val first = detector.analyze("com.test.app", "x9q2mz8v4pr7ka.example.com")
        val second = detector.analyze("com.test.app", "x9q2mz8v4pr7ka.example.com")

        assertTrue(first.any { it.type == DnsAnomalyDetector.AnomalyType.HIGH_ENTROPY })
        assertTrue(second.none { it.type == DnsAnomalyDetector.AnomalyType.HIGH_ENTROPY })
    }

    @Test
    fun cdnHostnamesAreNotFlaggedAsGenerated() {
        val detector = DnsAnomalyDetector()

        val anomalies = detector.analyze("com.honeyboard", "d127n5jiwb3j0b.cloudfront.net")

        assertEquals(false, anomalies.any { it.type == DnsAnomalyDetector.AnomalyType.HIGH_ENTROPY })
    }
}
