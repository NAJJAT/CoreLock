package com.privacyguard.app.core.behavior

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BehaviorNotifierTest {

    private fun finding(severity: BehaviorSeverity, host: String = "upload.example") = BehaviorFinding(
        packageName = "com.example", hostname = host, severity = severity,
        title = "Upload spike", summary = "", evidence = emptyList(), kind = BehaviorKind.UPLOAD_SPIKE,
    )

    @Test
    fun only_high_findings_notify() {
        val (due, _) = BehaviorNotifier.select(listOf(finding(BehaviorSeverity.MEDIUM), finding(BehaviorSeverity.HIGH, "big.example")), emptyMap(), now = 1)
        assertEquals(listOf("big.example"), due.map { it.hostname })
    }

    @Test
    fun the_same_finding_notifies_once_a_day_even_after_a_restart() {
        val high = finding(BehaviorSeverity.HIGH)
        val (first, record) = BehaviorNotifier.select(listOf(high), emptyMap(), now = 1_000)
        assertEquals(1, first.size)
        // A restart reads the saved record back.
        assertTrue(BehaviorNotifier.select(listOf(high), record, now = 1_000 + 60_000).first.isEmpty())
        assertEquals(1, BehaviorNotifier.select(listOf(high), record, now = 1_000 + BehaviorNotifier.REPEAT_MS).first.size)
    }

    @Test
    fun the_record_holds_no_readable_host_names() {
        val (_, record) = BehaviorNotifier.select(listOf(finding(BehaviorSeverity.HIGH)), emptyMap(), now = 1)
        assertTrue(record.keys.none { it.contains("example") })
    }

    @Test
    fun only_big_background_uploads_are_high() {
        assertEquals(BehaviorSeverity.MEDIUM, BehaviorDnaAnalyzer.uploadSeverity(2_000_000))
        assertEquals(BehaviorSeverity.HIGH, BehaviorDnaAnalyzer.uploadSeverity(BehaviorDnaAnalyzer.UPLOAD_HIGH_BYTES))
    }
}
