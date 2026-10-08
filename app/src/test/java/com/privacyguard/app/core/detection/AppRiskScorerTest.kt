package com.privacyguard.app.core.detection

import com.privacyguard.app.core.sensors.SensorPurpose
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.db.SensorEventEntity
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRiskScorerTest {

    @Test
    fun aBusyMessengerScoresNothing() {
        // WhatsApp: hundreds of background connections, megabytes, its own servers only.
        val connections = List(300) {
            ConnectionEntity(appUid = 1, packageName = "com.whatsapp", domain = "g.whatsapp.net",
                destinationIp = "157.240.1.1", bytesSent = 2_000_000, wasBackground = true, encryptionStatus = "UNKNOWN")
        }
        val inputs = BehaviorBlocker.inputsFrom("com.whatsapp", connections, emptyList(), SensorPurpose.COMMUNICATION, 0)
        assertEquals(0, AppRiskScorer.score(inputs).score)
    }

    @Test
    fun oneSignalAloneStopsBelowHigh() {
        val risk = AppRiskScorer.score(AppRiskInputs(sensors = SensorRiskSummary(unseenUses = 3)))
        assertEquals(45, risk.score)
        assertEquals("MED", risk.level)
        val strong = AppRiskScorer.score(AppRiskInputs(stalkerwareScore = 100))
        assertTrue(strong.score < AppRiskScorer.HIGH)
    }

    @Test
    fun twoIndependentSignalsReachHigh() {
        val risk = AppRiskScorer.score(AppRiskInputs(sensors = SensorRiskSummary(unseenUses = 1, uploadAfterUses = 1)))
        assertEquals(80, risk.score)
        assertEquals("HIGH", risk.level)
        assertEquals(2, risk.reasons.size)
    }

    @Test
    fun expectedAndNormalUsesDoNotCount() {
        fun event(severity: String, expected: Boolean) = SensorEventEntity(
            sensor = "MIC", startTime = 1, endTime = 2, source = "MIC", packageName = "com.example",
            confidence = "CONFIRMED", attributionMethod = "t", screenOn = false, locked = true,
            quietHours = true, inCall = false, openedRecently = false, severity = severity, userMarkedExpected = expected,
        )
        val summary = SensorRiskSummary.from(
            listOf(event("CRITICAL", expected = true), event("NONE", expected = false), event("CRITICAL", expected = false)),
            SensorPurpose.UNKNOWN,
        )
        assertEquals(1, summary.unseenUses)
    }

    @Test
    fun behaviorRulesFollowTheProtectionLevel() {
        val engine = FilterEngine()
        engine.addRule(FilterRule(id = "b70", label = "", action = FilterRule.Action.DENY,
            source = FilterRule.Source.BEHAVIOR, matchPackage = "com.spy"))
        engine.addRule(FilterRule(id = "b40", label = "", action = FilterRule.Action.DENY,
            source = FilterRule.Source.BEHAVIOR_STRICT, matchPackage = "com.meh"))
        fun blocked(pkg: String) = engine.evaluate(10_000, pkg, null, "1.2.3.4", 443, 6).isBlocked

        engine.blockLevel = FilterEngine.BlockLevel.MINIMAL
        assertEquals(false, blocked("com.spy"))
        assertEquals(false, blocked("com.meh"))
        engine.blockLevel = FilterEngine.BlockLevel.STANDARD
        assertEquals(true, blocked("com.spy"))
        assertEquals(false, blocked("com.meh"))
        engine.blockLevel = FilterEngine.BlockLevel.STRICT
        assertEquals(true, blocked("com.spy"))
        assertEquals(true, blocked("com.meh"))
    }
}
