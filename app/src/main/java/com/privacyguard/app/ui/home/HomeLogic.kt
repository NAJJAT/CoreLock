package com.privacyguard.app.ui.home

import androidx.annotation.StringRes
import com.privacyguard.app.R
import com.privacyguard.app.ui.components.ProtectionState
import com.privacyguard.app.vpn.UnexpectedStop
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
     * A recorded [stopReason] (another VPN took over, the packet path kept failing) is also
     * unexpected, although that stop cleared [shouldBeActive] on its way out.
     */
    fun protectionState(
        service: PrivacyVpnService.State,
        shouldBeActive: Boolean,
        stopReason: UnexpectedStop.Reason? = null,
        paused: Boolean = false,
    ): ProtectionState = when (service) {
        PrivacyVpnService.State.RUNNING -> ProtectionState.ON
        PrivacyVpnService.State.STARTING -> ProtectionState.STARTING
        PrivacyVpnService.State.STOPPED ->
            // A pause is a stop the user asked for, with a way back already set (D1).
            if (paused) ProtectionState.PAUSED
            else if (shouldBeActive || stopReason != null) ProtectionState.STOPPED_UNEXPECTEDLY else ProtectionState.OFF
    }

    /**
     * Local midnight at or before [now]: "Blocked today" counts from here. Calendar, not
     * java.time: java.time needs Android 8 and the app supports Android 7 without
     * desugaring. On a day whose midnight falls in a DST gap, this is the first real
     * minute of the day (as java.time's atStartOfDay would give).
     */
    fun startOfDay(now: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        com.privacyguard.app.data.repository.BlockStats.startOfDay(now, zone)

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
        ProtectionState.PAUSED -> R.string.ui_hero_paused
    }

    @StringRes
    fun heroSubtitle(state: ProtectionState, level: String, stopReason: UnexpectedStop.Reason? = null): Int = when (state) {
        ProtectionState.ON -> when (level) {
            LEVEL_MINIMAL -> R.string.ui_hero_sub_minimal
            LEVEL_STRICT -> R.string.ui_hero_sub_strict
            else -> R.string.ui_hero_sub_standard
        }
        ProtectionState.OFF -> R.string.ui_hero_off_sub
        ProtectionState.STARTING -> R.string.ui_hero_starting_sub
        // Takes the end time as its argument (HomeScreen formats it).
        ProtectionState.PAUSED -> R.string.ui_hero_paused_sub
        // Only what happened, in plain words; no reason recorded means a crash or a killed process.
        ProtectionState.STOPPED_UNEXPECTEDLY -> when (stopReason) {
            UnexpectedStop.Reason.OTHER_VPN -> R.string.ui_home_stopped_other_vpn
            UnexpectedStop.Reason.PACKET_PATH_FAILED -> R.string.ui_home_stopped_failed
            null -> R.string.ui_home_stopped_sub
        }
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
        /** Protection died with the app process and Android says the system killed it ([stoppedBySystem]). */
        val stoppedBySystem: Boolean = false,
        val newHighAlert: HighAlert? = null,
        val whatsNew: Boolean = false,
        val strictSuggestion: com.privacyguard.app.ui.dashboard.StrictSuggestion? = null,
    )

    data class HighAlert(val title: String, val timestamp: Long)

    sealed interface Attention {
        data class PrivateDns(val host: String) : Attention
        data object UsageAccess : Attention
        /**
         * [canDismiss]: "Restricted" always stops protection, so it stays. "Optimized" (Android's
         * default) is shown only after the system really stopped protection, and can be dismissed.
         */
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
            // Not for every new install: general battery advice belongs in onboarding (Phase 7).
            com.privacyguard.app.core.power.BatteryCheck.State.OPTIMIZED ->
                if (inputs.stoppedBySystem && !inputs.batteryDismissed) return Attention.Battery(canDismiss = true)
            com.privacyguard.app.core.power.BatteryCheck.State.UNRESTRICTED -> {}
        }
        inputs.newHighAlert?.let { return Attention.NewAlert(it) }
        if (inputs.whatsNew) return Attention.WhatsNew
        inputs.strictSuggestion?.let { return Attention.Strict(it) }
        return null
    }

    /**
     * Protection was stopped by the system: it stopped unexpectedly with no reason the app
     * recorded (not another VPN, not the packet path), so its process died, and Android's
     * exit record names a system kill. A crash or the user is not the battery's fault; on
     * Android 10 and older there is no record, so the card is not shown there.
     */
    fun stoppedBySystem(
        state: ProtectionState,
        stopReason: UnexpectedStop.Reason?,
        lastExit: com.privacyguard.app.core.power.SystemStopCheck.Result,
    ): Boolean = state == ProtectionState.STOPPED_UNEXPECTEDLY && stopReason == null &&
        lastExit == com.privacyguard.app.core.power.SystemStopCheck.Result.SYSTEM

    /** Same threshold the old Home and the alert inbox use for red alerts. */
    const val HIGH_ALERT_SEVERITY = 8
    /** A high alert is "new" on Home for this long, unless opened or dismissed first. */
    const val HIGH_ALERT_WINDOW_MS = 24L * 60L * 60L * 1000L

    const val LEVEL_MINIMAL = "MINIMAL"
    const val LEVEL_STANDARD = "STANDARD"
    const val LEVEL_STRICT = "STRICT"
}
