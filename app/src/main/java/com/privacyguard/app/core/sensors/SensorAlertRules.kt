package com.privacyguard.app.core.sensors

import java.util.Locale

enum class Severity { NONE, LOW, HIGH, CRITICAL }

/** The rule engine's decision for one use. */
data class SensorVerdict(
    val severity: Severity,
    /** One sentence a non-technical person understands. */
    val reason: String,
    /** Independent risk signals that fired, for scoring (see AppsViewModel). */
    val signals: Set<Signal>,
)

enum class Signal { SCREEN_OFF_OR_LOCKED, QUIET_HOURS_UNOPENED, UPLOAD_AFTER_USE, PURPOSE_MISMATCH, BACKGROUND_USE }

/**
 * Decides whether a camera/microphone use deserves an alarm. Normal use never
 * does: the app you are using, a call, face unlock, the assistant's wake word.
 * Busy traffic alone never raises anything — an upload only counts after a use
 * the user did not start.
 *
 * Pure: everything it needs is passed in, so it is tested with fake clocks and
 * fake device state.
 */
object SensorAlertRules {

    /** Face unlock opens the front camera for a moment right after the screen wakes. */
    private const val FACE_UNLOCK_WINDOW_MS = 3_000L
    private const val FACE_UNLOCK_MAX_MS = 5_000L

    private val FACE_UNLOCK_PACKAGES = setOf(
        "android", "com.android.systemui", "com.samsung.android.biometrics.app.setting",
        "com.samsung.android.bio.face.service", "com.google.android.as",
    )

    /**
     * @param final false while the use is still running (no upload figure yet, the
     *   duration unknown); true once the upload window after it has passed.
     * @param appLabel display name of the attributed app, if any.
     * @param purpose what the attributed app is for ([SensorPurpose.UNKNOWN] if unattributed).
     * @param userConfirmedBefore the user said "It was me" for this app and sensor before.
     */
    fun evaluate(
        use: SensorUse,
        attribution: Attribution,
        context: SensorContext,
        appLabel: String?,
        purpose: SensorPurpose,
        userConfirmedBefore: Boolean,
        final: Boolean,
    ): SensorVerdict {
        val what = if (use.sensor == SensorType.CAMERA) "camera" else "microphone"
        val who = subject(attribution, appLabel)
        val Who = who.replaceFirstChar { it.titlecase(Locale.getDefault()) }

        // ── Expected system uses: never alarms ────────────────────────────────
        if (use.sensor == SensorType.MIC && use.source == "HOTWORD") {
            return SensorVerdict(Severity.NONE, "The voice assistant listened for its wake word.", emptySet())
        }
        if (use.sensor == SensorType.MIC && context.inCall) {
            return SensorVerdict(Severity.NONE, "The microphone was used during a call.", emptySet())
        }
        if (isFaceUnlock(use, attribution, context, final)) {
            return SensorVerdict(Severity.NONE, "Face unlock used the front camera to unlock your phone.", emptySet())
        }

        val unseen = !context.screenOn || context.locked
        // The user is using the app: they opened it, or it was identified while the
        // screen was on and unlocked (Android 11+ lets apps start the camera or mic
        // only from the foreground) and nothing says they did not open it.
        val userStarted = context.openedRecently == true ||
            (attribution.confidence != Confidence.UNKNOWN && !unseen && context.openedRecently != false)
        // Quiet hours only count when we know the user did not open the app.
        val quietUnopened = context.quietHours && context.openedRecently == false
        val mismatch = attribution.packageName != null && !purpose.expects(use.sensor)
        // An upload counts only if it is the identified app's own, or the use happened
        // unseen: with an unidentified app, the "busiest candidate" is often just a
        // messenger syncing, and busy traffic alone must never raise an alarm.
        val burst = final && context.networkBurst && !userStarted && (attribution.packageName != null || unseen)

        val signals = buildSet {
            if (unseen) add(Signal.SCREEN_OFF_OR_LOCKED)
            if (quietUnopened) add(Signal.QUIET_HOURS_UNOPENED)
            if (burst) add(Signal.UPLOAD_AFTER_USE)
            if (mismatch) add(Signal.PURPOSE_MISMATCH)
            if (!userStarted) add(Signal.BACKGROUND_USE)
        }

        var severity: Severity
        val reason: String
        when {
            unseen -> {
                val state = if (!context.screenOn) "your screen was off" else "your phone was locked"
                // A recording the user started and then locked the phone on (voice memo).
                severity = if (context.openedRecently == true && purpose.expects(use.sensor)) Severity.LOW else Severity.CRITICAL
                reason = "$Who used the $what while $state."
            }
            burst -> {
                severity = Severity.HIGH
                reason = "$Who used the $what, then sent ${formatBytes(context.uploadBytes ?: 0)} within a minute."
            }
            quietUnopened && !userStarted -> {
                severity = Severity.HIGH
                reason = "$Who used the $what at ${hourLabel(context.hourOfDay)}, without you opening it."
            }
            mismatch -> {
                severity = Severity.LOW
                reason = "$Who used the $what, though it is ${purpose.label}."
            }
            userStarted -> {
                severity = Severity.NONE
                reason = "$Who used the $what while you were using it."
            }
            else -> {
                severity = Severity.NONE
                reason = "$Who used the $what while your screen was on."
            }
        }

        // A job that has nothing to do with the sensor makes any finding worse.
        if (mismatch && severity == Severity.LOW && !userStarted) severity = Severity.HIGH
        if (mismatch && severity == Severity.HIGH && unseen) severity = Severity.CRITICAL

        // The user taught us this app does this: two steps down.
        if (userConfirmedBefore && severity != Severity.NONE) {
            severity = Severity.values()[(severity.ordinal - 2).coerceAtLeast(0)]
        }
        return SensorVerdict(severity, reason, signals)
    }

    /**
     * Front camera, briefly, right after the screen woke while locked, by the
     * system or by nobody we can name. While the use is still running the duration
     * is unknown, so only the timing is checked.
     */
    private fun isFaceUnlock(use: SensorUse, a: Attribution, c: SensorContext, final: Boolean): Boolean {
        if (use.sensor != SensorType.CAMERA) return false
        val justWoke = c.sinceScreenOnMs != null && c.sinceScreenOnMs <= FACE_UNLOCK_WINDOW_MS
        val system = a.packageName == null || a.packageName in FACE_UNLOCK_PACKAGES
        val brief = !final || (use.durationMs ?: Long.MAX_VALUE) <= FACE_UNLOCK_MAX_MS
        return justWoke && system && brief
    }

    private fun subject(a: Attribution, label: String?): String {
        val name = label ?: a.packageName
        return when {
            name == null -> "an app"
            a.confidence == Confidence.CONFIRMED -> name
            a.confidence == Confidence.LIKELY -> "probably $name"
            else -> "an app"
        }
    }

    private fun hourLabel(hour: Int): String = when (hour) {
        0 -> "midnight"
        in 1..11 -> "$hour a.m."
        12 -> "noon"
        else -> "${hour - 12} p.m."
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> "${bytes / 1024} KB"
    }
}
