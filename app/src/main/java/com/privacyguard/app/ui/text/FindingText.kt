package com.privacyguard.app.ui.text

import android.content.Context
import android.text.format.Formatter
import com.privacyguard.app.R
import com.privacyguard.app.core.behavior.BehaviorFinding
import com.privacyguard.app.core.behavior.BehaviorKind
import com.privacyguard.app.core.behavior.BehaviorSeverity
import com.privacyguard.app.core.detection.MismatchSeverity
import com.privacyguard.app.core.detection.PermissionMismatchFinding
import java.text.NumberFormat

/**
 * Localized text for analyzer findings. The analyzers stay plain Kotlin (no
 * Context, unit-testable) and expose a stable kind plus raw values; this maps
 * them to string resources, so a finding reads in the user's language even
 * when it was computed before they switched.
 */
object FindingText {

    private fun pct(n: Long) = NumberFormat.getPercentInstance().format(n / 100.0)

    fun appLabel(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    fun title(context: Context, f: BehaviorFinding): String = context.getString(
        when (f.kind) {
            BehaviorKind.NEW_DESTINATION -> R.string.bdna_new_dest
            BehaviorKind.UNUSUAL_HOUR -> R.string.bdna_unusual_hour
            BehaviorKind.UPLOAD_SPIKE -> R.string.bdna_upload_spike
            BehaviorKind.BACKGROUND_BEACON -> R.string.bdna_beacon
        }
    )

    fun summary(context: Context, f: BehaviorFinding): String {
        val v = f.values
        return when (f.kind) {
            BehaviorKind.NEW_DESTINATION ->
                context.getString(R.string.bdna_new_dest_summary, f.hostname, appLabel(context, f.packageName))
            BehaviorKind.UNUSUAL_HOUR -> context.getString(
                R.string.bdna_unusual_hour_summary, f.hostname,
                android.text.format.DateUtils.formatDateTime(
                    context,
                    java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.HOUR_OF_DAY, (v["hour"] ?: 0).toInt()); set(java.util.Calendar.MINUTE, 0)
                    }.timeInMillis,
                    android.text.format.DateUtils.FORMAT_SHOW_TIME,
                ),
            )
            BehaviorKind.UPLOAD_SPIKE -> context.getString(
                R.string.bdna_upload_spike_summary, f.hostname,
                Formatter.formatShortFileSize(context, v["bytesOut"] ?: 0),
            )
            BehaviorKind.BACKGROUND_BEACON -> context.getString(
                R.string.bdna_beacon_summary, f.hostname,
                context.resources.getQuantityString(R.plurals.bdna_seconds, (v["intervalSec"] ?: 0).toInt(), (v["intervalSec"] ?: 0).toInt()),
            )
        }
    }

    fun severity(context: Context, s: BehaviorSeverity): String = context.getString(
        when (s) {
            BehaviorSeverity.HIGH -> R.string.alerts_high
            BehaviorSeverity.MEDIUM -> R.string.alerts_medium
            BehaviorSeverity.LOW -> R.string.alerts_low
        }
    )

    fun title(context: Context, f: PermissionMismatchFinding): String = when (f.id) {
        "location_without_permission" -> context.getString(R.string.pmm_location)
        "social_identity_without_contacts" -> context.getString(R.string.pmm_social)
        "utility_tracker_stack" -> context.getString(R.string.pmm_utility)
        "background_upload_mismatch" -> context.getString(R.string.pmm_upload)
        else -> f.title
    }

    fun summary(context: Context, f: PermissionMismatchFinding): String {
        val a = f.args
        if (a.isEmpty()) return f.summary
        return when (f.id) {
            "location_without_permission" -> context.getString(R.string.pmm_location_summary, a[0])
            "social_identity_without_contacts" -> context.getString(R.string.pmm_social_summary, a[0])
            "utility_tracker_stack" -> context.getString(R.string.pmm_utility_summary, a[0])
            "background_upload_mismatch" -> context.getString(
                R.string.pmm_upload_summary, a[0], Formatter.formatShortFileSize(context, a[1].toLongOrNull() ?: 0),
            )
            else -> f.summary
        }
    }

    fun evidence(context: Context, f: PermissionMismatchFinding): List<String> {
        val a = f.args
        if (a.isEmpty()) return f.evidence
        return when (f.id) {
            "location_without_permission" -> listOf(
                context.getString(R.string.pmm_ev_hosts, a[1]),
                context.getString(R.string.pmm_ev_no_location),
            )
            "social_identity_without_contacts" -> listOf(
                context.getString(R.string.pmm_ev_hosts, a[1]),
                context.getString(R.string.pmm_ev_no_contacts),
            )
            "utility_tracker_stack" -> listOfNotNull(
                context.getString(R.string.pmm_ev_trackers, a[1]),
                a.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { context.getString(R.string.pmm_ev_tracker_hosts, it) },
            )
            "background_upload_mismatch" -> listOf(
                context.getString(R.string.pmm_ev_bg, pct(a[2].toLongOrNull() ?: 0)),
                context.getString(R.string.pmm_ev_night, NumberFormat.getIntegerInstance().format(a[3].toLongOrNull() ?: 0)),
                context.getString(R.string.pmm_ev_sensitive, NumberFormat.getIntegerInstance().format(a[4].toLongOrNull() ?: 0)),
            )
            else -> f.evidence
        }
    }

    fun severity(context: Context, s: MismatchSeverity): String = context.getString(
        when (s) {
            MismatchSeverity.HIGH -> R.string.alerts_high
            MismatchSeverity.MEDIUM -> R.string.alerts_medium
            MismatchSeverity.LOW -> R.string.alerts_low
        }
    )

    fun dnsAnomalyTitle(context: Context, type: String): String = context.getString(
        when (type) {
            "DNS_TUNNELING" -> R.string.dns_tunneling
            "DGA_BEACON" -> R.string.dns_dga_beacon
            "HIGH_ENTROPY" -> R.string.dns_high_entropy
            "HIGH_FREQUENCY" -> R.string.dns_high_frequency
            "EXCESSIVE_SUBDOMAINS" -> R.string.dns_many_subdomains
            "LONG_LABEL" -> R.string.dns_long_label
            else -> R.string.dns_unknown
        }
    )

    fun dnsAnomalyExplanation(context: Context, type: String): String = context.getString(
        when (type) {
            "DNS_TUNNELING" -> R.string.dns_tunneling_desc
            "DGA_BEACON" -> R.string.dns_dga_beacon_desc
            "HIGH_ENTROPY" -> R.string.dns_high_entropy_desc
            "HIGH_FREQUENCY" -> R.string.dns_high_frequency_desc
            "EXCESSIVE_SUBDOMAINS" -> R.string.dns_many_subdomains_desc
            "LONG_LABEL" -> R.string.dns_long_label_desc
            else -> R.string.dns_unknown
        }
    )

    fun trackerCategory(context: Context, c: com.privacyguard.app.core.tracker.TrackerCategory): String = context.getString(
        when (c) {
            com.privacyguard.app.core.tracker.TrackerCategory.ADVERTISING -> R.string.tcat_ads
            com.privacyguard.app.core.tracker.TrackerCategory.ANALYTICS -> R.string.tcat_analytics
            com.privacyguard.app.core.tracker.TrackerCategory.CRASH_REPORTING -> R.string.tcat_crash
            com.privacyguard.app.core.tracker.TrackerCategory.FINGERPRINTING -> R.string.tcat_fingerprinting
            com.privacyguard.app.core.tracker.TrackerCategory.SOCIAL -> R.string.tcat_social
            com.privacyguard.app.core.tracker.TrackerCategory.PROFILING -> R.string.tcat_profiling
            com.privacyguard.app.core.tracker.TrackerCategory.OTHER -> R.string.tcat_other
        }
    )

    /** One plain sentence on what this kind of tracker does with what it receives. */
    fun trackerCategoryExplanation(context: Context, c: com.privacyguard.app.core.tracker.TrackerCategory): String = context.getString(
        when (c) {
            com.privacyguard.app.core.tracker.TrackerCategory.ADVERTISING -> R.string.tcat_ads_desc
            com.privacyguard.app.core.tracker.TrackerCategory.ANALYTICS -> R.string.tcat_analytics_desc
            com.privacyguard.app.core.tracker.TrackerCategory.CRASH_REPORTING -> R.string.tcat_crash_desc
            com.privacyguard.app.core.tracker.TrackerCategory.FINGERPRINTING -> R.string.tcat_fingerprinting_desc
            com.privacyguard.app.core.tracker.TrackerCategory.SOCIAL -> R.string.tcat_social_desc
            com.privacyguard.app.core.tracker.TrackerCategory.PROFILING -> R.string.tcat_profiling_desc
            com.privacyguard.app.core.tracker.TrackerCategory.OTHER -> R.string.tcat_other_desc
        }
    )
}