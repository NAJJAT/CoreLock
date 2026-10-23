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
 *       --es package <pkg> -p com.privacyguard.app.enterprise
 *
 * Make the next VPN start fail right after the tunnel is up (tests the cleanup path):
 *   adb shell am broadcast -a com.privacyguard.debug.FAIL_NEXT_START -p com.privacyguard.app.enterprise
 *
 * Debug builds never set FLAG_SECURE (SecureScreens), so screens can be captured as is.
 */
class DebugHooksReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
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
            ACTION_SET_DUAL_VPN -> setDualVpn(context, intent)
            ACTION_SET_SETTING -> setSetting(context, intent)
            ACTION_UPDATE_BLOCKLISTS -> updateBlocklists(context)
            ACTION_PCAP -> pcap(context, intent)
            ACTION_DUMP_ALERTS -> dumpAlerts(context, intent)
            ACTION_PROBE_DNS -> probeDns()
            ACTION_DOMAIN_RULE -> domainRule(context, intent)
            ACTION_DELETE_TEST_ALERTS -> deleteTestAlerts(
                context,
                intent.getStringExtra("package") ?: LEGACY_TEST_APP,
                intent.getBooleanExtra("apply", false),
            )
            // Recovery tests (V-18). FAIL_WORKER: reports the TUN reader as dead, as its
            // uncaught-exception handler would, so the tunnel is rebuilt in place.
            // CRASH_PROCESS: an uncaught exception on the main thread, like a UI crash.
            ACTION_FAIL_WORKER -> {
                val reader = Thread.getAllStackTraces().keys.firstOrNull { it.name == "tun-reader" }
                if (reader == null) Log.w(TAG, "FAIL_WORKER: no tun-reader thread")
                else com.privacyguard.vpn.WorkerGuard.threadHandler.uncaughtException(reader, IllegalStateException("debug hook"))
            }
            ACTION_CRASH_PROCESS -> android.os.Handler(android.os.Looper.getMainLooper()).post {
                throw IllegalStateException("Debug hook: simulated crash")
            }
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

    /**
     * What the alert inbox holds since a moment (types and apps, no domains):
     *   adb shell am broadcast -a com.privacyguard.debug.DUMP_ALERTS --el since EPOCH_MS -p PKG
     */
    private fun dumpAlerts(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val since = intent.getLongExtra("since", 0L)
                val app = context.applicationContext as android.app.Application
                val items = com.privacyguard.app.ui.alerts.AlertFeed(app).loadItems().filter { it.timestamp >= since }
                Log.i(TAG, "ALERTS since=$since total=${items.size} byType=${items.groupingBy { it.type }.eachCount()}")
                items.groupingBy { "${it.type}|${it.title}|${it.packageName}" }.eachCount()
                    .entries.sortedByDescending { it.value }.take(15)
                    .forEach { (k, n) -> Log.i(TAG, "ALERTS $n × $k") }
                val hours = items.groupingBy { it.timestamp / 3_600_000L }.eachCount().toSortedMap()
                Log.i(TAG, "ALERTS perHour=${hours.values}")
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Packet capture and the Diagnostics DNS probe without the UI:
     *   adb shell am broadcast -a com.privacyguard.debug.PCAP --ez start true|false -p PKG
     *   adb shell am broadcast -a com.privacyguard.debug.PROBE_DNS -p PKG
     */
    private fun pcap(context: Context, intent: Intent) {
        val pcap = com.privacyguard.app.core.pcap.PcapWriter
        if (intent.getBooleanExtra("start", true)) pcap.startCapture(context) else pcap.stopCapture()
        Log.i(TAG, "PCAP capturing=${pcap.isCapturing()} file=${pcap.getCurrentFilePath() ?: pcap.lastCapture(context)}")
    }

    private fun probeDns() {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i(TAG, "PROBE_DNS upstream=${com.privacyguard.platform.android.PrivacyVpnService.probeUpstreamDns()}")
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Downloads every blocklist now and logs the domains per category:
     *   adb shell am broadcast -a com.privacyguard.debug.UPDATE_BLOCKLISTS -p PKG
     */
    private fun updateBlocklists(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                com.privacyguard.app.core.blocklist.BlocklistManager.initialize(context)
                val result = com.privacyguard.app.core.blocklist.BlocklistManager.updateAllBlocklists()
                val dao = AppDatabase.getInstance(context).blocklistDao()
                val perCategory = dao.categories().associateWith { dao.enabledCountIn(listOf(it)) }
                Log.i(TAG, "BLOCKLISTS result=$result perCategory=$perCategory sources=${dao.sources()}")
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Settings switches without the UI:
     *   adb shell am broadcast -a com.privacyguard.debug.SET_SETTING --es name ct|notifications --ez value true -p PKG
     */
    private fun setSetting(context: Context, intent: Intent) {
        val s = com.privacyguard.app.data.local.preferences.SettingsPreferences.getInstance(context)
        val value = intent.getBooleanExtra("value", false)
        when (intent.getStringExtra("name")) {
            "ct" -> s.setCtMonitoringEnabled(value)
            "notifications" -> s.setNotificationsEnabled(value)
            else -> { Log.w(TAG, "SETTING unknown name"); return }
        }
        Log.i(TAG, "SETTING ${intent.getStringExtra("name")}=$value")
    }

    /**
     * Domain rules without the UI (risky = the user accepted the malware warning):
     *   adb shell am broadcast -a com.privacyguard.debug.DOMAIN_RULE --es domain d --ez allow true [--ez risky true] -p PKG
     *   adb shell am broadcast -a com.privacyguard.debug.DOMAIN_RULE --es delete ID -p PKG
     * Also logs one domain from the malware lists (SAMPLE malware=...) to test with.
     */
    private fun domainRule(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val config = com.privacyguard.app.data.db.ConfigDatabase.getInstance(context)
                val repo = com.privacyguard.app.data.repository.RulesRepo(config.rulesDao(), com.privacyguard.core.filter.FilterEngine())
                val delete = intent.getStringExtra("delete")
                val domain = intent.getStringExtra("domain")
                when {
                    delete != null -> { repo.deleteRule(delete); Log.i(TAG, "RULE deleted $delete") }
                    domain != null -> {
                        val allow = intent.getBooleanExtra("allow", true)
                        val base = if (allow) com.privacyguard.core.filter.FilterRule.allowDomain(domain)
                            else com.privacyguard.core.filter.FilterRule.blockDomain(domain, source = com.privacyguard.core.filter.FilterRule.Source.USER)
                        val rule = base.copy(id = "debug-$domain", overridesSecurity = allow && intent.getBooleanExtra("risky", false))
                        repo.upsertRule(rule)
                        Log.i(TAG, "RULE ${rule.id} allow=$allow overridesSecurity=${rule.overridesSecurity}")
                    }
                }
                val sample = AppDatabase.getInstance(context).blocklistDao()
                    .getEntriesByCategory(com.privacyguard.app.data.repository.BlocklistRepo.CAT_MALWARE)
                    .filter { !it.domain.startsWith("*") }.map { it.domain }.shuffled().take(40)
                Log.i(TAG, "SAMPLE malware=$sample")
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Configures dual-hop without the UI (the phone may be locked during a test run):
     *   adb shell am broadcast -a com.privacyguard.debug.SET_DUAL_VPN --ez enabled true \
     *       --es hop1 127.0.0.1 --ei hop1port 1080 --es hop2 127.0.0.1 --ei hop2port 1081 \
     *       [--ez blockudp true] [--ez doh true] -p PKG_OF_APP
     * Takes effect for new connections at once.
     */
    private fun setDualVpn(context: Context, intent: Intent) {
        val s = com.privacyguard.app.data.local.preferences.SettingsPreferences.getInstance(context)
        intent.getStringExtra("hop1")?.let(s::setDualVpnHop1Host)
        if (intent.hasExtra("hop1port")) s.setDualVpnHop1Port(intent.getIntExtra("hop1port", 1080))
        intent.getStringExtra("hop2")?.let(s::setDualVpnHop2Host)
        if (intent.hasExtra("hop2port")) s.setDualVpnHop2Port(intent.getIntExtra("hop2port", 1080))
        if (intent.hasExtra("blockudp")) s.setDualVpnBlockUdp(intent.getBooleanExtra("blockudp", false))
        if (intent.hasExtra("doh")) s.setDohEnabled(intent.getBooleanExtra("doh", false))
        if (intent.hasExtra("enabled")) s.setDualVpnEnabled(intent.getBooleanExtra("enabled", false))
        Log.i(TAG, "DUAL config valid=${s.getDualVpnConfig().isValid} blockUdp=${s.dualVpnBlockUdp.value} doh=${s.dohEnabled.value}")
    }

    /**
     * Removes alerts that tests created, nothing else: TLS alerts of [testApp] (by
     * default the removed com.privacyguard.testapp, whose rows may remain on development
     * phones) and DNS anomalies for nip.io names (the leak-test canaries). Lists them
     * only, unless apply is true:
     *   adb shell am broadcast -a com.privacyguard.debug.DELETE_TEST_ALERTS \
     *       [--es package <pkg>] [--ez apply true] -p com.privacyguard.app.enterprise
     */
    private fun deleteTestAlerts(context: Context, testApp: String, apply: Boolean) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val sql = AppDatabase.getInstance(context).openHelper.writableDatabase
                // The package comes from the broadcast: bound, never pasted into the SQL.
                val tls = "FROM tls_alerts WHERE packageName = ?"
                val dns = "FROM dns_anomalies WHERE domain = 'nip.io' OR domain LIKE '%.nip.io'"
                sql.query("SELECT id, alertType, packageName, timestamp $tls", arrayOf<Any>(testApp)).use { c ->
                    while (c.moveToNext()) Log.i(TAG, "TEST_ALERT tls id=${c.getLong(0)} ${c.getString(1)} ${c.getString(2)} at=${c.getLong(3)}")
                }
                sql.query("SELECT id, anomalyType, packageName, domain, timestamp $dns").use { c ->
                    while (c.moveToNext()) Log.i(TAG, "TEST_ALERT dns id=${c.getLong(0)} ${c.getString(1)} ${c.getString(2)} ${c.getString(3)} at=${c.getLong(4)}")
                }
                if (apply) {
                    val nTls = sql.delete("tls_alerts", "packageName = ?", arrayOf(testApp))
                    val nDns = sql.delete("dns_anomalies", "domain = 'nip.io' OR domain LIKE '%.nip.io'", null)
                    Log.i(TAG, "TEST_ALERT deleted tls=$nTls dns=$nDns")
                } else {
                    Log.i(TAG, "TEST_ALERT dry run; add --ez apply true to delete")
                }
            } finally {
                pending.finish()
            }
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
        const val ACTION_FAIL_NEXT_START = "com.privacyguard.debug.FAIL_NEXT_START"
        const val ACTION_DUMP_THREADS = "com.privacyguard.debug.DUMP_THREADS"
        const val ACTION_DUMP_PROFILES = "com.privacyguard.debug.DUMP_PROFILES"
        const val ACTION_DUMP_NETWORK = "com.privacyguard.debug.DUMP_NETWORK"
        const val ACTION_START_VPN = "com.privacyguard.debug.START_VPN"
        const val ACTION_SET_DUAL_VPN = "com.privacyguard.debug.SET_DUAL_VPN"
        const val ACTION_SET_SETTING = "com.privacyguard.debug.SET_SETTING"
        const val ACTION_UPDATE_BLOCKLISTS = "com.privacyguard.debug.UPDATE_BLOCKLISTS"
        const val ACTION_PCAP = "com.privacyguard.debug.PCAP"
        const val ACTION_DUMP_ALERTS = "com.privacyguard.debug.DUMP_ALERTS"
        const val ACTION_PROBE_DNS = "com.privacyguard.debug.PROBE_DNS"
        const val ACTION_DOMAIN_RULE = "com.privacyguard.debug.DOMAIN_RULE"
        const val ACTION_DELETE_TEST_ALERTS = "com.privacyguard.debug.DELETE_TEST_ALERTS"
        const val ACTION_FAIL_WORKER = "com.privacyguard.debug.FAIL_WORKER"
        const val ACTION_CRASH_PROCESS = "com.privacyguard.debug.CRASH_PROCESS"
        // Removed test app (branch archive/before-cleanup); old alerts of it may remain.
        private const val LEGACY_TEST_APP = "com.privacyguard.testapp"
        const val DEBUG_PREFS = "debug_hooks"
        const val KEY_FAIL_NEXT_START = "fail_next_start"
    }
}
