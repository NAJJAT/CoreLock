package com.privacyguard.app.core.detection

import com.privacyguard.app.core.sensors.SensorPurpose
import com.privacyguard.app.core.sensors.SensorType
import com.privacyguard.app.data.db.SensorEventEntity

/** Camera & mic behavior of one app over the scoring window. */
data class SensorRiskSummary(
    /** Uses that started with the screen off or the phone locked (and were not calls, face unlock, …). */
    val unseenUses: Int = 0,
    /** Quiet-hours uses by the app when the user had not opened it. */
    val quietHoursUses: Int = 0,
    /** Uses followed by an upload the user did not start. */
    val uploadAfterUses: Int = 0,
    /** Uses of a sensor the app's purpose does not explain (a game using the mic). */
    val purposeMismatchUses: Int = 0,
) {
    companion object {
        /**
         * Reads the stored events of one app. Uses the user marked "It was me" do not
         * count, and neither do uses the rules judged normal (severity NONE).
         */
        fun from(events: List<SensorEventEntity>, purpose: SensorPurpose): SensorRiskSummary {
            val relevant = events.filter { !it.userMarkedExpected && it.severity != "NONE" }
            return SensorRiskSummary(
                unseenUses = relevant.count { !it.screenOn || it.locked },
                quietHoursUses = relevant.count { it.quietHours && !it.openedRecently },
                uploadAfterUses = relevant.count { it.networkBurst },
                purposeMismatchUses = relevant.count { !purpose.expects(SensorType.valueOf(it.sensor)) },
            )
        }
    }
}

/** What feeds an app's risk score. Traffic volume deliberately does not. */
data class AppRiskInputs(
    /** Third-party tracker destinations (the app's own company excluded). */
    val trackerDestinations: Int = 0,
    val cleartextConnections: Int = 0,
    val weakTlsConnections: Int = 0,
    val stalkerwareScore: Int = 0,
    val sensors: SensorRiskSummary = SensorRiskSummary(),
)

data class AppRisk(
    /** 0–100. */
    val score: Int,
    /** Independent signals that contributed (plain language). */
    val reasons: List<String>,
) {
    val level: String get() = when {
        score >= AppRiskScorer.HIGH -> "HIGH"
        score >= AppRiskScorer.MEDIUM -> "MED"
        else -> "LOW"
    }
}

/**
 * Scores an app on what it does, not how busy it is. A messenger keeps a socket
 * open around the clock and sends megabytes of media; that is its job and scores
 * nothing. Points come from trackers, unencrypted traffic, stalkerware traits and
 * camera/microphone use the user did not start.
 *
 * HIGH needs at least two independent signals: one alone, however strong, stops
 * at MED.
 */
object AppRiskScorer {
    const val HIGH = 70
    const val MEDIUM = 40

    fun score(inputs: AppRiskInputs): AppRisk {
        val parts = mutableListOf<Pair<Int, String>>()
        val s = inputs.sensors
        if (s.unseenUses > 0) parts += 45 to "Used the camera or microphone while the screen was off or locked (${s.unseenUses}×)"
        if (s.uploadAfterUses > 0) parts += 35 to "Uploaded data right after using the camera or microphone (${s.uploadAfterUses}×)"
        if (s.quietHoursUses > 0) parts += 25 to "Used the camera or microphone at night without being opened (${s.quietHoursUses}×)"
        if (s.purposeMismatchUses > 0) parts += 25 to "Used the camera or microphone, which its purpose does not explain"
        if (inputs.stalkerwareScore >= 40) parts += (inputs.stalkerwareScore / 2).coerceAtMost(40) to "Has traits of monitoring software"
        if (inputs.cleartextConnections > 0) parts += 15 to "Sends data unencrypted (${inputs.cleartextConnections} connections)"
        if (inputs.trackerDestinations > 0) parts += 10 to "Contacts tracking companies (${inputs.trackerDestinations})"
        if (inputs.weakTlsConnections > 0) parts += 5 to "Uses outdated encryption"

        var score = parts.sumOf { it.first }.coerceIn(0, 100)
        if (parts.size < 2) score = score.coerceAtMost(HIGH - 1)
        return AppRisk(score, parts.sortedByDescending { it.first }.map { it.second })
    }
}
