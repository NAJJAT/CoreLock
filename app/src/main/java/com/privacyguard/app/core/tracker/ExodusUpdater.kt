package com.privacyguard.app.core.tracker

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Fetches the Exodus Privacy tracker list and merges it into [TrackerDatabase].
 * Results are cached in SharedPreferences so subsequent cold starts don't need
 * network. Refreshes weekly.
 */
object ExodusUpdater {

    private const val PREFS        = "exodus_cache"
    private const val KEY_JSON     = "trackers_json"
    private const val KEY_UPDATED  = "last_updated_ms"
    private const val TTL_MS       = 7 * 24 * 60 * 60 * 1000L  // 7 days
    private const val API_URL      = "https://reports.exodus-privacy.eu.org/api/trackers"

    private val dynamicTrackers = mutableListOf<TrackerEntry>()

    fun initialize(context: Context) {
        val prefs = prefs(context)
        val cached = prefs.getString(KEY_JSON, null)
        if (cached != null) parse(cached)
    }

    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        val prefs = prefs(context)
        val lastUpdated = prefs.getLong(KEY_UPDATED, 0L)
        if (System.currentTimeMillis() - lastUpdated < TTL_MS) return@withContext true

        return@withContext try {
            if (!API_URL.startsWith("https://", ignoreCase = true)) return@withContext false
            val conn = URL(API_URL).openConnection() as HttpsURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout    = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode in 200..299) {
                val finalUrl = conn.url.toString()
                if (!finalUrl.startsWith("https://", ignoreCase = true)) {
                    conn.disconnect()
                    return@withContext false
                }
                val json = conn.inputStream.bufferedReader().readText()
                prefs.edit()
                    .putString(KEY_JSON, json)
                    .putLong(KEY_UPDATED, System.currentTimeMillis())
                    .apply()
                parse(json)
                true
            } else false
        } catch (e: Exception) {
            Log.w("ExodusUpdater", "Tracker refresh failed: ${e.message}")
            false
        }
    }

    private fun parse(json: String) {
        runCatching {
            val root     = JSONObject(json)
            val trackers = root.optJSONObject("trackers") ?: return
            val result   = mutableListOf<TrackerEntry>()

            val keys = trackers.keys()
            while (keys.hasNext()) {
                val key     = keys.next()
                val obj     = trackers.optJSONObject(key) ?: continue
                val name    = obj.optString("name").ifBlank { continue }
                val website = obj.optString("website", "")
                val netSig  = obj.optString("network_signature", "")
                val codeSig = obj.optString("code_signature", "")
                val cat     = obj.optString("categories", "")

                val domains = netSig.split("|")
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.contains("\\") && it.length < 100 }

                val classPatterns = codeSig.split("|")
                    .map { it.trim().trimEnd('.') }
                    .filter { it.isNotBlank() && it.length < 100 }

                if (domains.isEmpty() && classPatterns.isEmpty()) continue

                result += TrackerEntry(
                    name          = name,
                    company       = extractCompany(website, name),
                    category      = mapCategory(cat),
                    domains       = domains,
                    classPatterns = classPatterns,
                )
            }

            synchronized(dynamicTrackers) {
                dynamicTrackers.clear()
                dynamicTrackers.addAll(result)
            }
        }
    }

    fun lookupByDomain(host: String): TrackerEntry? {
        val h = host.lowercase().trimStart('.')
        return synchronized(dynamicTrackers) {
            dynamicTrackers.firstOrNull { t ->
                t.domains.any { d -> h == d || h.endsWith(".$d") }
            }
        }
    }

    fun lookupByClassPattern(className: String): TrackerEntry? {
        val c = className.lowercase()
        return synchronized(dynamicTrackers) {
            dynamicTrackers.firstOrNull { t ->
                t.classPatterns.any { p -> c.startsWith(p.lowercase()) }
            }
        }
    }

    private fun extractCompany(website: String, name: String): String {
        if (website.isBlank()) return name
        return runCatching {
            URL(website).host.removePrefix("www.").substringBefore('.')
                .replaceFirstChar { it.uppercaseChar() }
        }.getOrElse { name }
    }

    private fun mapCategory(raw: String): TrackerCategory = when {
        raw.contains("Advertis", ignoreCase = true)      -> TrackerCategory.ADVERTISING
        raw.contains("Analytics", ignoreCase = true)     -> TrackerCategory.ANALYTICS
        raw.contains("Crash", ignoreCase = true)         -> TrackerCategory.CRASH_REPORTING
        raw.contains("Fingerprint", ignoreCase = true)   -> TrackerCategory.FINGERPRINTING
        raw.contains("Social", ignoreCase = true)        -> TrackerCategory.SOCIAL
        raw.contains("Profil", ignoreCase = true)        -> TrackerCategory.PROFILING
        else                                             -> TrackerCategory.OTHER
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
