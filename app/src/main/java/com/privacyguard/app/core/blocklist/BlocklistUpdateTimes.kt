package com.privacyguard.app.core.blocklist

import android.content.Context

/**
 * When each downloaded list last downloaded and parsed successfully, kept across
 * restarts. A refresh fetches only the lists older than its cut-off, so a retry
 * fetches the lists that failed, not the ones that just arrived, and a list that
 * failed stays due until it succeeds.
 */
interface BlocklistUpdateTimes {
    /** Milliseconds since the epoch, or 0 when the list never succeeded. */
    fun lastSuccess(source: BlocklistSource): Long
    fun recordSuccess(source: BlocklistSource, at: Long)
}

/**
 * SharedPreferences, like ExodusUpdater's own update time: the project has no
 * DataStore dependency. Not sensitive (list names and times only); backups are off.
 */
class PrefsBlocklistUpdateTimes(context: Context) : BlocklistUpdateTimes {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun lastSuccess(source: BlocklistSource): Long = prefs.getLong(KEY_PREFIX + source.name, 0L)

    // commit, not apply: the worker may be stopped right after a list is stored.
    // Runs on the worker's IO thread, never the main thread.
    @android.annotation.SuppressLint("ApplySharedPref")
    override fun recordSuccess(source: BlocklistSource, at: Long) {
        prefs.edit().putLong(KEY_PREFIX + source.name, at).commit()
    }

    internal companion object {
        const val PREFS = "blocklist_updates"
        const val KEY_PREFIX = "last_success_"
    }
}

/**
 * The last download of each list refused as too small (ADR 0011), kept for
 * Diagnostics: the old and new name counts and when.
 */
interface BlocklistRejections {
    fun record(source: BlocklistSource, stored: Int, downloaded: Int, at: Long)
}

data class BlocklistRejection(val stored: Int, val downloaded: Int, val at: Long)

/** Next to the success times; list names and counts only, not sensitive. */
class PrefsBlocklistRejections(context: Context) : BlocklistRejections {
    private val prefs = context.applicationContext.getSharedPreferences(PrefsBlocklistUpdateTimes.PREFS, Context.MODE_PRIVATE)

    @android.annotation.SuppressLint("ApplySharedPref")
    override fun record(source: BlocklistSource, stored: Int, downloaded: Int, at: Long) {
        prefs.edit().putString(KEY_PREFIX + source.name, "$stored|$downloaded|$at").commit()
    }

    fun last(source: BlocklistSource): BlocklistRejection? =
        prefs.getString(KEY_PREFIX + source.name, null)?.split('|')?.takeIf { it.size == 3 }?.let { (s, d, at) ->
            BlocklistRejection(s.toIntOrNull() ?: return null, d.toIntOrNull() ?: return null, at.toLongOrNull() ?: return null)
        }

    private companion object {
        const val KEY_PREFIX = "rejected_too_small_"
    }
}
