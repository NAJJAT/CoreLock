package com.privacyguard.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.privacyguard.app.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Debug-only test hooks, reachable from `adb shell am broadcast` alone (see the
 * DUMP permission in src/debug/AndroidManifest.xml).
 *
 * End the 7-day TLS learning period for one app:
 *   adb shell am broadcast -a com.privacyguard.debug.TLS_END_LEARNING \
 *       --es package com.privacyguard.testapp -p com.privacyguard.app
 *
 * Allow screenshots (FLAG_SECURE is otherwise set on debuggable builds):
 *   adb shell am broadcast -a com.privacyguard.debug.ALLOW_SCREENSHOTS \
 *       --ez enabled true -p com.privacyguard.app
 */
class DebugHooksReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ALLOW_SCREENSHOTS -> {
                val enabled = intent.getBooleanExtra("enabled", true)
                context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_ALLOW_SCREENSHOTS, enabled).apply()
                Log.i(TAG, "Screenshots allowed=$enabled (reopen the app)")
            }
            ACTION_TLS_END_LEARNING -> endLearning(context, intent.getStringExtra("package") ?: return)
        }
    }

    private fun endLearning(context: Context, pkg: String) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(context).tlsBaselineDao()
                val backdated = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)
                val profiles = dao.profilesFor(pkg)
                profiles.forEach { dao.upsertProfile(it.copy(learningStartedAt = backdated, lastUpdateAt = null)) }
                Log.i(TAG, "Ended TLS learning for $pkg (${profiles.size} profile(s))")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "DebugHooks"
        const val ACTION_TLS_END_LEARNING = "com.privacyguard.debug.TLS_END_LEARNING"
        const val ACTION_ALLOW_SCREENSHOTS = "com.privacyguard.debug.ALLOW_SCREENSHOTS"
        const val DEBUG_PREFS = "debug_hooks"
        const val KEY_ALLOW_SCREENSHOTS = "allow_screenshots"
    }
}
