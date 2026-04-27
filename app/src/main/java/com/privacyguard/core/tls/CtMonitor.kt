package com.privacyguard.core.tls

import android.util.Log
import kotlinx.coroutines.*
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

class CtMonitor(
    private val onNewCert: (domain: String, entries: List<CtEntry>) -> Unit
) {
    companion object {
        private const val TAG = "CtMonitor"
        private const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000L // 6 hours
        private const val MAX_WATCHED_DOMAINS = 50
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val watchedDomains = ConcurrentHashMap.newKeySet<String>()
    private val lastChecked = ConcurrentHashMap<String, Long>()
    private val lastMaxCertId = ConcurrentHashMap<String, Long>()

    fun addDomain(hostname: String) {
        if (watchedDomains.size >= MAX_WATCHED_DOMAINS) return
        val apex = apexDomain(hostname)
        watchedDomains.add(apex)
    }

    fun startMonitoring() {
        scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                watchedDomains.toList().forEach { domain ->
                    val last = lastChecked[domain] ?: 0L
                    if (now - last > CHECK_INTERVAL_MS) {
                        checkDomain(domain)
                        lastChecked[domain] = now
                    }
                }
                delay(60_000L)
            }
        }
    }

    fun stop() = scope.cancel()

    private suspend fun checkDomain(domain: String) = withContext(Dispatchers.IO) {
        try {
            val url = "https://crt.sh/?q=%25.$domain&output=json&limit=10"
            val request = Request.Builder().url(url).build()
            val resp = client.newCall(request).execute()
            val body = resp.body?.string() ?: return@withContext
            if (!body.startsWith("[")) return@withContext
            val json = JSONArray(body)
            val entries = (0 until json.length()).map { i ->
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
            val knownMaxId = lastMaxCertId[domain] ?: 0L
            val newEntries = entries.filter { it.id > knownMaxId }
            if (newEntries.isNotEmpty()) {
                lastMaxCertId[domain] = entries.maxOf { it.id }
                onNewCert(domain, newEntries)
            } else if (entries.isNotEmpty()) {
                lastMaxCertId[domain] = maxOf(lastMaxCertId[domain] ?: 0L, entries.maxOf { it.id })
            }
        } catch (e: Exception) {
            Log.w(TAG, "CT check failed for $domain: ${e.message}")
        }
    }

    private fun apexDomain(hostname: String): String {
        val parts = hostname.trimStart('.').split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else hostname
    }
}
