package com.privacyguard.app.ui.home

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI-only flags for Home: nothing here changes protection, and nothing is sensitive
 * (shared preferences are excluded from backup anyway, data_extraction_rules.xml).
 */
class HomePreferences private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _whatsNew = MutableStateFlow(prefs.getString(KEY_WHATS_NEW, null) == WHATS_NEW_SHOW)
    /** True while the one-time "Some things moved" card (D12) should show. */
    val whatsNew: StateFlow<Boolean> = _whatsNew.asStateFlow()

    private val _alertSeenAt = MutableStateFlow(prefs.getLong(KEY_ALERT_SEEN_AT, 0L))
    /** Alerts up to this time were opened or dismissed on Home. */
    val alertSeenAt: StateFlow<Long> = _alertSeenAt.asStateFlow()

    private val _batteryDismissed = MutableStateFlow(prefs.getBoolean(KEY_BATTERY_DISMISSED, false))
    val batteryDismissed: StateFlow<Boolean> = _batteryDismissed.asStateFlow()

    /**
     * Run once at every app start, before onboarding can finish. The first time 1.1 runs it
     * decides for good: onboarding already completed means this phone had 1.0 (an update),
     * so the card shows; otherwise it is a fresh install and the card never shows, also
     * not after onboarding completes later.
     */
    fun decideWhatsNew(onboardingCompleted: Boolean) {
        if (prefs.contains(KEY_WHATS_NEW)) return
        val value = if (onboardingCompleted) WHATS_NEW_SHOW else WHATS_NEW_DONE
        prefs.edit().putString(KEY_WHATS_NEW, value).apply()
        _whatsNew.value = value == WHATS_NEW_SHOW
    }

    /** Dismissed, or one of its links opened: never shown again. */
    fun finishWhatsNew() {
        prefs.edit().putString(KEY_WHATS_NEW, WHATS_NEW_DONE).apply()
        _whatsNew.value = false
    }

    fun markAlertsSeen(upTo: Long) {
        if (upTo <= _alertSeenAt.value) return
        prefs.edit().putLong(KEY_ALERT_SEEN_AT, upTo).apply()
        _alertSeenAt.value = upTo
    }

    fun dismissBattery() {
        prefs.edit().putBoolean(KEY_BATTERY_DISMISSED, true).apply()
        _batteryDismissed.value = true
    }

    companion object {
        private const val PREFS_NAME = "corelock_home"
        private const val KEY_WHATS_NEW = "whats_new_1_1"
        private const val KEY_ALERT_SEEN_AT = "alert_seen_at"
        private const val KEY_BATTERY_DISMISSED = "battery_dismissed"
        private const val WHATS_NEW_SHOW = "show"
        private const val WHATS_NEW_DONE = "done"

        @Volatile private var instance: HomePreferences? = null

        fun getInstance(context: Context): HomePreferences =
            instance ?: synchronized(this) {
                instance ?: HomePreferences(context.applicationContext).also { instance = it }
            }
    }
}
