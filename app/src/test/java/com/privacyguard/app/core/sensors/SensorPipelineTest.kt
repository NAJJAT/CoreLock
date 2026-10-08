package com.privacyguard.app.core.sensors

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The whole decision chain on a fake clock and fake device state: system
 * callbacks → use → attribution → context → verdict.
 */
class SensorPipelineTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(hour: Int, minute: Int = 0) = Calendar.getInstance(utc).apply {
        clear(); set(2026, Calendar.OCTOBER, 8, hour, minute)
    }.timeInMillis

    private class FakeDevice(var screenOn: Boolean, var locked: Boolean, var inCall: Boolean = false) : DeviceState {
        override fun isScreenOn() = screenOn
        override fun isLocked() = locked
        override fun isInCall() = inCall
        override fun lastScreenOnAt(): Long? = null
    }

    /** Runs one use from callbacks to verdict. */
    private fun run(
        startAt: Long,
        durationMs: Long,
        device: FakeDevice,
        sensor: SensorType,
        foreground: String?,
        opened: Set<String>?,
        holders: Set<String>,
        purpose: (String) -> SensorPurpose,
    ): SensorVerdict {
        var now = startAt
        var ended: SensorUse? = null
        val tracker = SensorSessionTracker({ now }, object : SensorSessionTracker.Listener {
            override fun onStarted(use: SensorUse) {}
            override fun onEnded(use: SensorUse) { ended = use }
        })
        tracker.startWatching()
        if (sensor == SensorType.CAMERA) tracker.cameraOpened("0") else tracker.recordingsChanged(mapOf(1 to "MIC"))
        now += durationMs
        if (sensor == SensorType.CAMERA) tracker.cameraClosed("0") else tracker.recordingsChanged(emptyMap())
        val use = ended!!

        val attribution = SensorAttributor.decide(
            use, now, "com.privacyguard.app", opRecords = null, foregroundPackage = foreground,
            holdsPermission = { it in holders }, candidates = { holders.toList() },
        )
        val context = SensorContextBuilder(
            device = device,
            quietHours = { QuietHours(23 * 60, 7 * 60) },
            openedSince = { _, _ -> opened },
            uidOf = { null },
            zone = { utc },
        ).atStart(use, attribution)
        val pkg = attribution.packageName
        return SensorAlertRules.evaluate(
            use, attribution, context, pkg, pkg?.let(purpose) ?: SensorPurpose.UNKNOWN,
            userConfirmedBefore = false, final = true,
        )
    }

    @Test
    fun whatsappInTheForegroundRaisesNoAlert() {
        val v = run(
            startAt = at(14), durationMs = 30_000,
            device = FakeDevice(screenOn = true, locked = false),
            sensor = SensorType.MIC,
            foreground = "com.whatsapp", opened = setOf("com.whatsapp"),
            holders = setOf("com.whatsapp"),
            purpose = { SensorPurpose.of(it, -1, false) },
        )
        assertEquals(Severity.NONE, v.severity)
    }

    @Test
    fun unknownAppScreenOffAt2amIsCritical() {
        val v = run(
            startAt = at(2), durationMs = 45_000,
            device = FakeDevice(screenOn = false, locked = true),
            sensor = SensorType.MIC,
            foreground = null, opened = emptySet(),
            holders = setOf("com.whatsapp", "com.example.recorder"),
            purpose = { SensorPurpose.of(it, -1, false) },
        )
        assertEquals(Severity.CRITICAL, v.severity)
        assertEquals("An app used the microphone while your screen was off.", v.reason)
    }

    @Test
    fun aCallAt2amWithTheScreenOffIsNormal() {
        val v = run(
            startAt = at(2), durationMs = 600_000,
            device = FakeDevice(screenOn = false, locked = true, inCall = true),
            sensor = SensorType.MIC,
            foreground = null, opened = emptySet(),
            holders = setOf("com.whatsapp"),
            purpose = { SensorPurpose.of(it, -1, false) },
        )
        assertEquals(Severity.NONE, v.severity)
    }

    @Test
    fun aGameOnScreenUsingTheMicAtNightIsRaised() {
        val v = run(
            startAt = at(1), durationMs = 10_000,
            device = FakeDevice(screenOn = true, locked = false),
            sensor = SensorType.MIC,
            foreground = "com.example.game", opened = emptySet(), // on screen, but not opened by the user recently
            holders = setOf("com.example.game"),
            purpose = { SensorPurpose.of(it, android.content.pm.ApplicationInfo.CATEGORY_GAME, false) },
        )
        assertEquals(Severity.HIGH, v.severity)
    }
}
