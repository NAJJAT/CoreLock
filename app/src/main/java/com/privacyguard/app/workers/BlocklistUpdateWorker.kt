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
import androidx.work.*
import com.privacyguard.app.core.blocklist.BlocklistManager
import com.privacyguard.app.core.blocklist.UpdateResult
import com.privacyguard.app.service.notification.NotificationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Worker for updating blocklists from external sources
 */
class BlocklistUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "blocklist_update"

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(
                repeatInterval = 7,
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
        }

        fun updateNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<BlocklistUpdateWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
        }

        fun cancelUpdates(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                BlocklistManager.initialize(applicationContext)
                when (val result = BlocklistManager.updateAllBlocklists()) {
                    is UpdateResult.Success -> {
                        NotificationService(applicationContext).blocklistUpdated("All sources", result.totalEntries)
                        Result.success()
                    }
                    is UpdateResult.Failure -> Result.retry()
                }
            } catch (_: Exception) {
                Result.retry()
            }
        }
    }
}
