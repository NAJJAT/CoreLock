/**
 * BlocklistUpdateWorker.kt
 * 
 * Background worker for updating blocklists weekly
 * 
 * What it does:
 * =============
 * - Runs weekly (or on-demand) to fetch latest blocklists
 * - Downloads from trusted sources (StevenBlack, EasyList, EasyPrivacy)
 * - Parses and stores in local database
 * - Updates in-memory blocklist for filtering
 * 
 * Why WorkManager?
 * ================
 * - Guaranteed execution (even after app restart)
 * - Respects device battery (only runs when conditions met)
 * - Supports periodic work (weekly)
 * - Handles network constraints (WiFi only)
 * 
 * Sources:
 * =========
 * - StevenBlack Unified Hosts: 100k+ domains
 * - EasyList: 70k+ ad domains
 * - EasyPrivacy: 15k+ tracker domains
 * 
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.workers

import android.content.Context
import android.util.Log
import androidx.work.*
import com.privacyguard.app.core.filter.BlocklistCategory
import com.privacyguard.app.core.filter.BlocklistEntry
import com.privacyguard.app.core.filter.BlocklistSource
import com.privacyguard.app.data.repository.BlocklistRepository
import com.privacyguard.app.vpn.firewall.DomainFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Worker for updating blocklists from external sources
 */
class BlocklistUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "BlocklistUpdateWorker"
        const val WORK_NAME = "blocklist_update"
        
        // Blocklist sources
        private val SOURCES = listOf(
            BlocklistSourceConfig(
                source = BlocklistSource.STEVENBLACK,
                url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                category = BlocklistCategory.ADVERTISING
            ),
            BlocklistSourceConfig(
                source = BlocklistSource.EASYLIST,
                url = "https://easylist.to/easylist/easylist.txt",
                category = BlocklistCategory.ADVERTISING
            ),
            BlocklistSourceConfig(
                source = BlocklistSource.EASYPRIVACY,
                url = "https://easylist.to/easylist/easyprivacy.txt",
                category = BlocklistCategory.ANALYTICS
            )
        )
        
        /**
         * Schedules periodic blocklist updates (weekly on WiFi)
         */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)  // WiFi only
                .setRequiresBatteryNotLow(true)
                .build()
            
            val workRequest = PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(
                repeatInterval = 7,  // Weekly
                repeatIntervalTimeUnit = TimeUnit.DAYS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    1,
                    TimeUnit.HOURS
                )
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            
            Log.d(TAG, "Scheduled weekly blocklist update")
        }
        
        /**
         * Triggers an immediate blocklist update (on-demand)
         */
        fun updateNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            val workRequest = OneTimeWorkRequestBuilder<BlocklistUpdateWorker>()
                .setConstraints(constraints)
                .build()
            
            WorkManager.getInstance(context).enqueue(workRequest)
            
            Log.d(TAG, "Triggered immediate blocklist update")
        }
        
        /**
         * Cancels scheduled updates
         */
        fun cancelUpdates(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled blocklist updates")
        }
    }
    
    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting blocklist update")
                
                val allEntries = mutableListOf<BlocklistEntry>()
                
                // Fetch from each source
                for (sourceConfig in SOURCES) {
                    try {
                        val entries = fetchBlocklist(sourceConfig)
                        allEntries.addAll(entries)
                        Log.d(TAG, "Fetched ${entries.size} entries from ${sourceConfig.source}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to fetch from ${sourceConfig.source}", e)
                    }
                }
                
                // Deduplicate
                val uniqueEntries = allEntries.distinctBy { it.normalizedDomain() }
                Log.d(TAG, "Total unique entries: ${uniqueEntries.size}")
                
                // Store in database
                val repository = BlocklistRepository(
                    com.privacyguard.app.data.db.AppDatabase.getInstance(applicationContext).blocklistDao()
                )
                repository.updateBlocklist(uniqueEntries)
                
                // Update in-memory filter
                val domainFilter = DomainFilter()
                domainFilter.updateBlocklist(uniqueEntries)
                
                Log.d(TAG, "Blocklist update completed successfully")
                Result.success()
                
            } catch (e: Exception) {
                Log.e(TAG, "Blocklist update failed", e)
                Result.retry()
            }
        }
    }
    
    /**
     * Fetches and parses blocklist from a source
     */
    private suspend fun fetchBlocklist(sourceConfig: BlocklistSourceConfig): List<BlocklistEntry> {
        return withContext(Dispatchers.IO) {
            val entries = mutableListOf<BlocklistEntry>()
            val url = URL(sourceConfig.url)
            val connection = url.openConnection()
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            
            val reader = BufferedReader(InputStreamReader(connection.getInputStream()))
            var line: String?
            val now = System.currentTimeMillis()
            
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                
                // Skip comments and empty lines
                if (currentLine.isEmpty() || currentLine.startsWith("#") || currentLine.startsWith("!")) {
                    continue
                }
                
                // Parse hosts file format: "0.0.0.0 domain.com" or "127.0.0.1 domain.com"
                val domain = when {
                    currentLine.startsWith("0.0.0.0") -> {
                        currentLine.substringAfter("0.0.0.0").trim().split(Regex("\\s+")).firstOrNull()
                    }
                    currentLine.startsWith("127.0.0.1") -> {
                        currentLine.substringAfter("127.0.0.1").trim().split(Regex("\\s+")).firstOrNull()
                    }
                    currentLine.startsWith("::1") -> {
                        currentLine.substringAfter("::1").trim().split(Regex("\\s+")).firstOrNull()
                    }
                    else -> {
                        // Assume line is just the domain
                        currentLine.trim().split(Regex("\\s+")).firstOrNull()
                    }
                }
                
                if (domain != null && domain.isNotBlank() && domain.contains('.') && domain.length < 255) {
                    entries.add(
                        BlocklistEntry(
                            domain = domain,
                            source = sourceConfig.source,
                            category = sourceConfig.category,
                            lastUpdated = now
                        )
                    )
                }
            }
            reader.close()
            
            entries
        }
    }
}

/**
 * Configuration for a blocklist source
 */
data class BlocklistSourceConfig(
    val source: BlocklistSource,
    val url: String,
    val category: BlocklistCategory
)

/**
 * Normalizes a domain for blocklist storage
 */
fun BlocklistEntry.normalizedDomain(): String {
    var result = domain.lowercase()
    if (result.endsWith('.')) {
        result = result.dropLast(1)
    }
    return result
}
