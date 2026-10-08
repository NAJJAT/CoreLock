package com.privacyguard.app.core.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The alarm rules with fake time, fake screen/lock state and fake uses.
 * Times are "local hour" via [SensorContext.hourOfDay]; nothing reads a real clock.
 */
class SensorAlertRulesTest {

    private val whatsapp = "com.whatsapp"

    private fun use(sensor: SensorType = SensorType.MIC, durationMs: Long? = 20_000, source: String = "VOICE_COMMUNICATION") =
        SensorUse(1, sensor, startTime = 1_000_000, endTime = durationMs?.let { 1_000_000 + it }, source = source)

    private fun ctx(
        screenOn: Boolean = true, locked: Boolean = false, hour: Int = 14, quiet: Boolean = false,
        inCall: Boolean = false, opened: Boolean? = true, sinceScreenOn: Long? = null, upload: Long? = null,
    ) = SensorContext(screenOn, locked, hour, quiet, inCall, opened, sinceScreenOn, upload)

    private fun attr(pkg: String?, c: Confidence = Confidence.CONFIRMED, method: String = "system app-ops record") =
        Attribution(pkg, c, method)

    private fun evaluate(
        use: SensorUse = use(), attribution: Attribution = attr(whatsapp), context: SensorContext = ctx(),
        label: String? = "WhatsApp", purpose: SensorPurpose = SensorPurpose.COMMUNICATION,
        confirmedBefore: Boolean = false, final: Boolean = true,
    ) = SensorAlertRules.evaluate(use, attribution, context, label, purpose, confirmedBefore, final)

    @Test
    fun whatsappVoiceNoteInTheForegroundIsNotAnAlarm() {
        val v = evaluate()
        assertEquals(Severity.NONE, v.severity)
        assertEquals("WhatsApp used the microphone while you were using it.", v.reason)
    }

    @Test
    fun unknownAppMicWithScreenOffAt2amIsCritical() {
        val v = evaluate(
            attribution = attr(null, Confidence.UNKNOWN, "no app on screen"),
            context = ctx(screenOn = false, locked = true, hour = 2, quiet = true, opened = null),
            label = null, purpose = SensorPurpose.UNKNOWN,
        )
        assertEquals(Severity.CRITICAL, v.severity)
        assertEquals("An app used the microphone while your screen was off.", v.reason)
        assertTrue(Signal.SCREEN_OFF_OR_LOCKED in v.signals)
    }

    @Test
    fun aCallWithTheScreenOffIsExpected() {
        val v = evaluate(context = ctx(screenOn = false, locked = true, inCall = true))
        assertEquals(Severity.NONE, v.severity)
    }

    @Test
    fun wakeWordListeningIsExpected() {
        val v = evaluate(use = use(source = "HOTWORD"), context = ctx(screenOn = false))
        assertEquals(Severity.NONE, v.severity)
    }

    @Test
    fun faceUnlockIsNotAnAlarm() {
        val v = evaluate(
            use = use(SensorType.CAMERA, durationMs = 1_200, source = "camera 1"),
            attribution = attr(null, Confidence.UNKNOWN, "no app on screen"),
            context = ctx(screenOn = true, locked = true, sinceScreenOn = 400, opened = null),
            label = null, purpose = SensorPurpose.UNKNOWN,
        )
        assertEquals(Severity.NONE, v.severity)
    }

    @Test
    fun aLongCameraUseAfterWakingIsNotFaceUnlock() {
        val v = evaluate(
            use = use(SensorType.CAMERA, durationMs = 60_000, source = "camera 1"),
            attribution = attr(null, Confidence.UNKNOWN, "no app on screen"),
            context = ctx(screenOn = true, locked = true, sinceScreenOn = 400, opened = null),
            label = null, purpose = SensorPurpose.UNKNOWN,
        )
        assertEquals(Severity.CRITICAL, v.severity)
    }

    @Test
    fun quietHoursByAnAppTheUserDidNotOpenIsHigh() {
        val v = evaluate(
            attribution = attr("com.example.app"),
            context = ctx(hour = 2, quiet = true, opened = false),
            label = "Example", purpose = SensorPurpose.UNKNOWN,
        )
        assertEquals(Severity.HIGH, v.severity)
        assertEquals("Example used the microphone at 2 a.m., without you opening it.", v.reason)
    }

    @Test
    fun quietHoursWithoutUsageAccessDoNotGuess() {
        // A late WhatsApp call while the screen is on: we cannot know who opened it.
        val v = evaluate(context = ctx(hour = 23, quiet = true, opened = null))
        assertEquals(Severity.NONE, v.severity)
    }

    @Test
    fun uploadAfterAnUnstartedUseIsHigh() {
        val v = evaluate(
            use = use(SensorType.CAMERA, source = "camera 0"),
            attribution = attr("com.example.app"),
            context = ctx(opened = false, upload = 3_500_000),
            label = "Example", purpose = SensorPurpose.UNKNOWN,
        )
        assertEquals(Severity.HIGH, v.severity)
        assertTrue(v.reason.contains("then sent 3.3 MB"))
    }

    @Test
    fun busyTrafficDuringNormalUseNeverAlarms() {
        // WhatsApp video call: camera on, user in the app, megabytes uploaded.
        val v = evaluate(use = use(SensorType.CAMERA, source = "camera 1"), context = ctx(opened = true, upload = 40_000_000))
        assertEquals(Severity.NONE, v.severity)
    }

    @Test
    fun aGameUsingTheMicrophoneIsRaised() {
        val v = evaluate(
            attribution = attr("com.example.game"),
            context = ctx(opened = false),
            label = "Puzzle Game", purpose = SensorPurpose.UNRELATED,
        )
        assertEquals(Severity.HIGH, v.severity)
        assertTrue(Signal.PURPOSE_MISMATCH in v.signals)
    }

    @Test
    fun aLikelyAttributionIsWordedAsLikely() {
        val v = evaluate(
            attribution = attr("com.example.app", Confidence.LIKELY, "app on screen holds the permission"),
            context = ctx(screenOn = false, opened = false),
            label = "Example", purpose = SensorPurpose.UNKNOWN,
        )
        assertTrue(v.reason.startsWith("Probably Example used"))
    }

    @Test
    fun itWasMeLowersSeverity() {
        val night = ctx(screenOn = false, locked = true, opened = false)
        val recorder = attr("com.sec.android.app.voicenote")
        assertEquals(Severity.CRITICAL, evaluate(attribution = recorder, context = night, purpose = SensorPurpose.RECORDER).severity)
        assertEquals(Severity.LOW, evaluate(attribution = recorder, context = night, purpose = SensorPurpose.RECORDER, confirmedBefore = true).severity)
    }

    @Test
    fun aRecordingTheUserStartedThenLockedIsLow() {
        val v = evaluate(
            attribution = attr("com.sec.android.app.voicenote"),
            context = ctx(screenOn = false, locked = true, opened = true),
            label = "Voice Recorder", purpose = SensorPurpose.RECORDER,
        )
        assertEquals(Severity.LOW, v.severity)
    }
}
