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

    private companion object {
        const val PREFS = "blocklist_updates"
        const val KEY_PREFIX = "last_success_"
    }
}
