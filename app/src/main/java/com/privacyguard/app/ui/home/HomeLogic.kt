package com.privacyguard.app.ui.home

import androidx.annotation.StringRes
import com.privacyguard.app.R
import com.privacyguard.app.ui.components.ProtectionState
import com.privacyguard.platform.android.PrivacyVpnService
import java.util.Calendar
import java.util.TimeZone

/**
 * Home's decisions, kept free of Android so they run as plain JVM tests
 * (DESIGN_SPEC_1.1.md §6.1).
 */
object HomeLogic {

    /**
     * The hero state from what the service publishes and what BootReceiver remembers.
     * "Stopped unexpectedly" means protection should be on (the last stop was not one the
     * user or the app asked for) and no service is running or starting: a crash, or the
     * process killed. A stop the user asked for clears [shouldBeActive] first.
     */
    fun protectionState(service: PrivacyVpnService.State, shouldBeActive: Boolean): ProtectionState = when (service) {
        PrivacyVpnService.State.RUNNING -> ProtectionState.ON
        PrivacyVpnService.State.STARTING -> ProtectionState.STARTING
        PrivacyVpnService.State.STOPPED -> if (shouldBeActive) ProtectionState.STOPPED_UNEXPECTEDLY else ProtectionState.OFF
    }

    /**
     * Local midnight at or before [now]: "Blocked today" counts from here. Calendar, not
     * java.time: java.time needs Android 8 and the app supports Android 7 without
     * desugaring. On a day whose midnight falls in a DST gap, this is the first real
     * minute of the day (as java.time's atStartOfDay would give).
     */
    fun startOfDay(now: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** The next local midnight after [now], when "today" starts again. */
    fun nextMidnight(now: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = startOfDay(now, zone)
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @StringRes
    fun heroTitle(state: ProtectionState): Int = when (state) {
        ProtectionState.ON -> R.string.ui_hero_on
        ProtectionState.OFF -> R.string.ui_hero_off
        ProtectionState.STARTING -> R.string.ui_hero_starting
        ProtectionState.STOPPED_UNEXPECTEDLY -> R.string.ui_hero_stopped
    }

    @StringRes
    fun heroSubtitle(state: ProtectionState, level: String): Int = when (state) {
        ProtectionState.ON -> when (level) {
            LEVEL_MINIMAL -> R.string.ui_hero_sub_minimal
            LEVEL_STRICT -> R.string.ui_hero_sub_strict
            else -> R.string.ui_hero_sub_standard
        }
        ProtectionState.OFF -> R.string.ui_hero_off_sub
        ProtectionState.STARTING -> R.string.ui_hero_starting_sub
        ProtectionState.STOPPED_UNEXPECTEDLY -> R.string.ui_home_stopped_sub
    }

    @StringRes
    fun levelName(level: String): Int = when (level) {
        LEVEL_MINIMAL -> R.string.set_level_minimal
        LEVEL_STRICT -> R.string.set_level_strict
        else -> R.string.set_level_standard
    }

    @StringRes
    fun levelDescription(level: String): Int = when (level) {
        LEVEL_MINIMAL -> R.string.ui_level_minimal_desc
        LEVEL_STRICT -> R.string.ui_level_strict_desc
        else -> R.string.ui_level_standard_desc
    }

    /** Level sheet order: least to most blocking. */
    val LEVELS = listOf(LEVEL_MINIMAL, LEVEL_STANDARD, LEVEL_STRICT)

    /** Everything the one Home attention card can be about, already read from the phone. */
    data class AttentionInputs(
        val privateDnsHost: String? = null,
        val usageAccessMissingForRule: Boolean = false,
        val battery: com.privacyguard.app.core.power.BatteryCheck.State =
            com.privacyguard.app.core.power.BatteryCheck.State.UNRESTRICTED,
        val batteryDismissed: Boolean = false,
        val newHighAlert: HighAlert? = null,
        val whatsNew: Boolean = false,
        val strictSuggestion: com.privacyguard.app.ui.dashboard.StrictSuggestion? = null,
    )

    data class HighAlert(val title: String, val timestamp: Long)

    sealed interface Attention {
        data class PrivateDns(val host: String) : Attention
        data object UsageAccess : Attention
        /** [canDismiss]: "Optimized" is Android's default and only a risk on some phones; "Restricted" always stops protection. */
        data class Battery(val canDismiss: Boolean) : Attention
        data class NewAlert(val alert: HighAlert) : Attention
        data object WhatsNew : Attention
        data class Strict(val suggestion: com.privacyguard.app.ui.dashboard.StrictSuggestion) : Attention
    }

    /**
     * At most one card, the first that applies (§5.2): Private DNS → (stopped unexpectedly:
     * the hero shows it, in red with "Turn on", so no second card repeats it) → (malware in
     * the last hour: not stored per list yet, see the Phase 3 report) → Usage Access → battery
     * → new high alert → what's new → recommendation (switch to Strict).
     */
    fun attention(inputs: AttentionInputs): Attention? {
        inputs.privateDnsHost?.let { return Attention.PrivateDns(it) }
        if (inputs.usageAccessMissingForRule) return Attention.UsageAccess
        when (inputs.battery) {
            com.privacyguard.app.core.power.BatteryCheck.State.RESTRICTED -> return Attention.Battery(canDismiss = false)
            com.privacyguard.app.core.power.BatteryCheck.State.OPTIMIZED ->
                if (!inputs.batteryDismissed) return Attention.Battery(canDismiss = true)
            com.privacyguard.app.core.power.BatteryCheck.State.UNRESTRICTED -> {}
        }
        inputs.newHighAlert?.let { return Attention.NewAlert(it) }
        if (inputs.whatsNew) return Attention.WhatsNew
        inputs.strictSuggestion?.let { return Attention.Strict(it) }
        return null
    }

    /** Same threshold the old Home and the alert inbox use for red alerts. */
    const val HIGH_ALERT_SEVERITY = 8
    /** A high alert is "new" on Home for this long, unless opened or dismissed first. */
    const val HIGH_ALERT_WINDOW_MS = 24L * 60L * 60L * 1000L

    /**
     * The "Some things moved" card (D12) says Statistics and HTTPS inspection are in
     * Settings › Advanced. That is true only once Phase 6 moves them, so the card stays
     * hidden until then; the update-or-fresh-install decision is already recorded.
     */
    const val WHATS_NEW_ENABLED = false

    const val LEVEL_MINIMAL = "MINIMAL"
    const val LEVEL_STANDARD = "STANDARD"
    const val LEVEL_STRICT = "STRICT"
}
