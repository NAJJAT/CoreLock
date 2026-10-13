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
 * Make the next VPN start fail right after the tunnel is up (tests the cleanup path):
 *   adb shell am broadcast -a com.privacyguard.debug.FAIL_NEXT_START -p com.privacyguard.app
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
            ACTION_DUMP_THREADS -> {
                // Thread dumps need root on user builds; log our own stacks instead.
                Thread.getAllStackTraces().forEach { (t, stack) ->
                    Log.w(TAG, "THREAD \"${t.name}\" ${t.state}")
                    stack.take(25).forEach { Log.w(TAG, "    at $it") }
                }
            }
            ACTION_DUMP_PROFILES -> dumpProfiles(context, intent.getStringExtra("package"))
            ACTION_DUMP_NETWORK -> dumpNetwork(context)
            // Restart protection after a test install (VPN consent must already be given).
            ACTION_START_VPN -> runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    context, com.privacyguard.platform.android.PrivacyVpnService.startIntent(context))
            }.onFailure { Log.w(TAG, "START_VPN refused: ${it.message}") }
            ACTION_FAIL_NEXT_START -> {
                context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_FAIL_NEXT_START, true).commit()
                Log.i(TAG, "Next VPN start will fail right after the tunnel is established")
            }
        }
    }

    /**
     * Behaviour-profile totals in logcat (the database is encrypted): used to check
     * that counts accumulate across VPN restarts.
     *   adb shell am broadcast -a com.privacyguard.debug.DUMP_PROFILES [--es package PKG] -p PKG_OF_APP
     */
    private fun dumpProfiles(context: Context, pkg: String?) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val rows = AppDatabase.getInstance(context).connectionProfileDao().allProfiles()
                    .filter { pkg == null || it.packageName == pkg }
                Log.i(TAG, "PROFILES package=${pkg ?: "*"} profiles=${rows.size} connections=${rows.sumOf { it.connectionCount }} " +
                    "maxCount=${rows.maxOfOrNull { it.connectionCount } ?: 0}")
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * What identifies the current network without location permission (for the
     * per-network dismissal question): our NetworkId, and the LinkProperties of every
     * non-VPN network, which ACCESS_NETWORK_STATE alone can read.
     */
    private fun dumpNetwork(context: Context) {
        val id = com.privacyguard.app.core.network.NetworkIdentity.current(context)
        Log.i(TAG, "NETWORK id=$id")
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        @Suppress("DEPRECATION")
        cm.allNetworks.forEach { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@forEach
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return@forEach
            val lp = cm.getLinkProperties(n) ?: return@forEach
            // RouteInfo.hasGateway and transportInfo need Android 10.
            val q = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
            Log.i(TAG, "NETWORK lp iface=${lp.interfaceName} addrs=${lp.linkAddresses} " +
                "gateways=${if (q) lp.routes.filter { it.hasGateway() }.map { it.gateway?.hostAddress } else lp.routes} " +
                "dns=${lp.dnsServers.map { it.hostAddress }} domains=${lp.domains} " +
                "transportInfo=${if (q) caps.transportInfo else "n/a"}")
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
        const val ACTION_FAIL_NEXT_START = "com.privacyguard.debug.FAIL_NEXT_START"
        const val ACTION_DUMP_THREADS = "com.privacyguard.debug.DUMP_THREADS"
        const val ACTION_DUMP_PROFILES = "com.privacyguard.debug.DUMP_PROFILES"
        const val ACTION_DUMP_NETWORK = "com.privacyguard.debug.DUMP_NETWORK"
        const val ACTION_START_VPN = "com.privacyguard.debug.START_VPN"
        const val DEBUG_PREFS = "debug_hooks"
        const val KEY_ALLOW_SCREENSHOTS = "allow_screenshots"
        const val KEY_FAIL_NEXT_START = "fail_next_start"
    }
}
