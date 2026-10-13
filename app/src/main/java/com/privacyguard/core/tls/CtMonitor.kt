package com.privacyguard.core.tls

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class CtEntry(
    val id: Long,
    val nameValue: String,
    val notBefore: String,
    val notAfter: String,
    val issuerName: String,
    val loggedAt: String,
)

/**
 * What CT has already seen per domain: the highest certificate id and when it was
 * last asked. Kept on disk so a VPN restart does not re-alert on old certificates.
 */
interface CtBaselineStore {
    fun get(domain: String): Pair<Long, Long>?   // (maxCertId, checkedAtMs)
    fun put(domain: String, maxCertId: Long, checkedAtMs: Long)
    fun clear()
}

class InMemoryCtBaselineStore : CtBaselineStore {
    private val map = ConcurrentHashMap<String, Pair<Long, Long>>()
    override fun get(domain: String) = map[domain]
    override fun put(domain: String, maxCertId: Long, checkedAtMs: Long) { map[domain] = maxCertId to checkedAtMs }
    override fun clear() = map.clear()
}

/**
 * Baseline in SharedPreferences. Keys are SHA-256 of the domain, so the file is
 * not a readable list of visited sites (the traffic database is encrypted; this
 * file is not).
 */
class SharedPrefsCtBaselineStore(context: android.content.Context) : CtBaselineStore {
    private val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    override fun get(domain: String): Pair<Long, Long>? =
        prefs.getString(key(domain), null)?.split('|')?.takeIf { it.size == 2 }
            ?.let { (id, at) -> (id.toLongOrNull() ?: return null) to (at.toLongOrNull() ?: return null) }

    override fun put(domain: String, maxCertId: Long, checkedAtMs: Long) {
        prefs.edit().putString(key(domain), "$maxCertId|$checkedAtMs").apply()
    }

    override fun clear() { prefs.edit().clear().apply() }

    private fun key(domain: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(domain.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val PREFS = "ct_baseline"
        fun clear(context: android.content.Context) =
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().clear().apply()
    }
}

/**
 * Certificate Transparency watch (opt-in): asks crt.sh about the domains apps
 * contact and alerts when a certificate appears that was not there before.
 *
 * Turning it off ([setEnabled] false) cancels the request in flight and forgets the
 * queued domains, so nothing more is sent to crt.sh.
 */
class CtMonitor(
    private val baseline: CtBaselineStore = InMemoryCtBaselineStore(),
    /** Sends the crt.sh query and returns the body; replaced in tests. */
    private val fetch: ((url: String) -> String?)? = null,
    /** Off in tests, which call [runDueChecks] themselves. */
    private val runLoop: Boolean = true,
    private val onNewCert: (domain: String, entries: List<CtEntry>) -> Unit,
) {
    companion object {
        private const val TAG = "CtMonitor"
        const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000L // 6 hours
        const val MAX_WATCHED_DOMAINS = 50

        fun queryUrl(domain: String) = "https://crt.sh/?q=%25.$domain&output=json&limit=10"

        fun parse(body: String): List<CtEntry>? {
            if (!body.startsWith("[")) return null
            val json = JSONArray(body)
            return (0 until json.length()).map { i ->
                json.getJSONObject(i).let { obj ->
                    CtEntry(
                        id = obj.optLong("id"),
                        nameValue = obj.optString("name_value"),
                        notBefore = obj.optString("not_before"),
                        notAfter = obj.optString("not_after"),
                        issuerName = obj.optString("issuer_name"),
                        loggedAt = obj.optString("entry_timestamp"),
                    )
                }
            }
        }
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val watchedDomains = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var enabled = false
    @Volatile private var loop: Job? = null
    @Volatile private var inFlight: Call? = null

    val isEnabled: Boolean get() = enabled

    /** Domains are only remembered while CT checks are on. */
    fun addDomain(hostname: String) {
        if (!enabled || watchedDomains.size >= MAX_WATCHED_DOMAINS) return
        watchedDomains.add(apexDomain(hostname))
    }

    @Synchronized
    fun setEnabled(on: Boolean) {
        enabled = on
        if (on) {
            if (runLoop && loop?.isActive != true) loop = scope.launch {
                while (isActive) {
                    runDueChecks(System.currentTimeMillis())
                    delay(60_000L)
                }
            }
        } else {
            if (loop != null) Log.i(TAG, "CT checks off: stopped")
            loop?.cancel()
            loop = null
            inFlight?.cancel()
            watchedDomains.clear()
        }
    }

    fun stop() {
        setEnabled(false)
        scope.cancel()
    }

    /** Checks every watched domain whose last check is older than [CHECK_INTERVAL_MS]. */
    fun runDueChecks(now: Long) {
        for (domain in watchedDomains.toList()) {
            if (!enabled) return
            val last = baseline.get(domain)?.second
            if (last == null || now - last > CHECK_INTERVAL_MS) checkDomain(domain, now)
        }
    }

    private fun checkDomain(domain: String, now: Long) {
        try {
            val body = (fetch ?: ::httpGet)(queryUrl(domain)) ?: return
            if (!enabled) return
            val entries = parse(body) ?: return
            val known = baseline.get(domain)
            val maxId = entries.maxOfOrNull { it.id } ?: 0L
            baseline.put(domain, maxOf(maxId, known?.first ?: 0L), now)
            // The first answer for a domain is its baseline: every certificate in it
            // already existed, so none of them is news.
            if (known == null) return
            val newEntries = entries.filter { it.id > known.first }
            if (newEntries.isNotEmpty()) onNewCert(domain, newEntries)
        } catch (e: Exception) {
            if (enabled) Log.w(TAG, "CT check failed: ${e.message}")
        }
    }

    private fun httpGet(url: String): String? {
        if (!enabled) return null
        val call = client.newCall(Request.Builder().url(url).build())
        inFlight = call
        // Turned off between the check above and here: setEnabled saw no call to cancel.
        if (!enabled) { call.cancel(); inFlight = null; return null }
        // No domain in the log: it would put browsing history in logcat.
        Log.i(TAG, "crt.sh request")
        try {
            return call.execute().use { it.body?.string() }
        } finally {
            inFlight = null
        }
    }

    private fun apexDomain(hostname: String): String {
        val parts = hostname.trimStart('.').split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else hostname
    }
}
