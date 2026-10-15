package com.privacyguard.app.ui.apps

import com.privacyguard.core.metadata.ConnectionProfile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Apps list showed Chrome as HIGH next to "0 destinations · 0 B": the counts were
 * from the last 24 h, the risk from every profile ever recorded. Both use 24 h now.
 */
class AppsRiskWindowTest {

    private val now = 10L * 24 * 60 * 60 * 1000
    private val since = now - 24L * 60 * 60 * 1000

    private fun profile(risk: Int, lastSeen: Long) =
        ConnectionProfile("com.android.chrome", "example.com", "203.0.113.7", 443, riskScore = risk, lastSeen = lastSeen)

    @Test
    fun old_high_risk_without_traffic_in_the_window_is_not_high() {
        val risk = AppsViewModel.windowRisk(listOf(profile(90, since - 1)), since, connectionRisk = null)
        assertEquals(AppsViewModel.NO_TRAFFIC_RISK, risk)
        assertEquals("LOW", item(risk).riskLevel)
    }

    @Test
    fun high_risk_seen_in_the_window_stays_high() {
        val risk = AppsViewModel.windowRisk(listOf(profile(90, since), profile(20, now)), since, connectionRisk = 12)
        assertEquals(90, risk)
        assertEquals("HIGH", item(risk).riskLevel)
    }

    @Test
    fun old_profiles_do_not_override_the_windows_connections() {
        val risk = AppsViewModel.windowRisk(listOf(profile(90, since - 1)), since, connectionRisk = 25)
        assertEquals(25, risk)
    }

    private fun item(risk: Int) = AppRiskItem(
        appName = "Chrome", packageName = "com.android.chrome", totalDestinations = 0, suspiciousCount = 0,
        cleartextCount = 0, totalBytesOut = 0, maxRiskScore = risk, isBlocked = false,
    )
}
