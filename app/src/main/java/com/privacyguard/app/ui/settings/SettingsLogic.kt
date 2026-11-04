package com.privacyguard.app.ui.settings

import androidx.annotation.StringRes
import com.privacyguard.app.R
import com.privacyguard.app.core.blocklist.BlocklistSource
import com.privacyguard.app.ui.theme.Appearance
import com.privacyguard.app.vpn.KillSwitch

/** Settings' pure parts (DESIGN_SPEC_1.1.md §6.5), apart from Android for tests. */
object SettingsLogic {

    /** "Keep history for": the 1.0 choices, unchanged. */
    val RETENTION_DAYS = listOf(7, 14, 30, 90)

    /** The lists that are downloaded; the others are built in or user-made and have no update time. */
    val DOWNLOADED_LISTS: List<BlocklistSource> = BlocklistSource.entries.filter { it.downloadUrl() != null }

    /** What the Blocklists row says about the last update. */
    sealed interface ListsUpdated {
        /** No downloaded list has succeeded yet: the built-in lists are in use. */
        data object Never : ListsUpdated

        /** [at]: the oldest success among the lists that succeeded, so "2 days ago" is true for every list. */
        data class At(val at: Long, val someMissing: Boolean) : ListsUpdated
    }

    /**
     * The oldest, not the newest, success: if one list stopped updating, "Updated today"
     * would hide it. A list that never succeeded is reported separately.
     */
    fun listsUpdated(lastSuccess: List<Long>): ListsUpdated {
        val done = lastSuccess.filter { it > 0 }
        if (done.isEmpty()) return ListsUpdated.Never
        return ListsUpdated.At(done.min(), someMissing = done.size < lastSuccess.size)
    }

    /**
     * The kill switch, as Android reports it. Only Android blocks traffic without the
     * tunnel, so "On" is shown only when Android says lockdown is on (CLAUDE.md §5.7).
     */
    @StringRes
    fun killSwitchSubtitle(status: KillSwitch.LockdownStatus): Int = when (status) {
        KillSwitch.LockdownStatus.ON -> R.string.ui_set_ks_on
        KillSwitch.LockdownStatus.OFF -> R.string.ui_set_ks_off
        KillSwitch.LockdownStatus.NOT_RUNNING -> R.string.ui_set_ks_not_running
        KillSwitch.LockdownStatus.UNSUPPORTED -> R.string.ui_set_ks_unsupported
    }

    /**
     * Shown while the user has not chosen. D4's default is System, but the 1.0 screens still
     * use fixed dark colours until Phase 10 moves them to tokens, so until then it is Dark.
     * The Settings row and the window read this one value, so they never disagree.
     */
    val DEFAULT_APPEARANCE = Appearance.DARK

    /** Stored as the enum name; anything else, or nothing yet, is [DEFAULT_APPEARANCE]. */
    fun appearanceOf(stored: String?): Appearance =
        Appearance.entries.firstOrNull { it.name == stored } ?: DEFAULT_APPEARANCE

    @StringRes
    fun appearanceLabel(appearance: Appearance): Int = when (appearance) {
        Appearance.SYSTEM -> R.string.ui_theme_system
        Appearance.LIGHT -> R.string.ui_theme_light
        Appearance.DARK -> R.string.ui_theme_dark
    }
}
