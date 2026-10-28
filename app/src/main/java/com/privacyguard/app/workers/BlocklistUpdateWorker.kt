/**
 * BlocklistUpdateWorker.kt
 * 
 * Background worker for updating blocklists weekly
 * 
 * What it does:
 * =============
 * - Runs weekly (or on-demand) to fetch latest blocklists
 * - Downloads the lists in BlocklistSource (HaGeZi, OISD small)
 * - Parses and stores in local database
 * - Updates in-memory blocklist for filtering
 * 
 * Why WorkManager?
 * ================
 * - Guaranteed execution (even after app restart)
 * - Respects device battery (only runs when conditions met)
 * - Supports periodic work (weekly)
 * - Handles network constraints: weekly on an unmetered network (battery not low);
 *   any network once a list has had no success for 14 days; retries failed lists
 *
 * Sources: see BlocklistSource and docs/HOW_IT_WORKS.md §3.5.
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
        /** Any network, but only for lists without a success for [STALE_DAYS]. */
        const val STALE_WORK_NAME = "blocklist_update_stale"
        const val NOW_WORK_NAME = "blocklist_update_now"

        private const val KEY_MAX_AGE_DAYS = "max_age_days"
        private const val KEY_REQUESTED_AT = "requested_at"
        // Not 7: a periodic run can come a little early and would then skip a week.
        internal const val WEEKLY_MAX_AGE_DAYS = 6
        internal const val STALE_DAYS = 14
        private val DAY_MS = TimeUnit.DAYS.toMillis(1)

        /** Called at every app start; KEEP leaves an existing schedule as it is. */
        fun schedulePeriodic(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, weeklyRequest())
            workManager.enqueueUniquePeriodicWork(STALE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, staleRequest())
        }

        /** Weekly, on Wi-Fi or another unmetered network. */
        internal fun weeklyRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(repeatInterval = 7, repeatIntervalTimeUnit = TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setInputData(workDataOf(KEY_MAX_AGE_DAYS to WEEKLY_MAX_AGE_DAYS))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build()

        /**
         * Daily on any network, for phones that are never on an unmetered network: it
         * fetches only lists whose last success is older than [STALE_DAYS], so otherwise
         * it ends at once without downloading anything.
         */
        internal fun staleRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(repeatInterval = 1, repeatIntervalTimeUnit = TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_MAX_AGE_DAYS to STALE_DAYS))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build()

        /** First download and "Refresh now": any network, every list once. */
        fun updateNow(context: Context) {
            // Unique: with retries, one request per app start would pile up while offline.
            WorkManager.getInstance(context).enqueueUniqueWork(NOW_WORK_NAME, ExistingWorkPolicy.REPLACE, nowRequest(System.currentTimeMillis()))
        }

        internal fun nowRequest(requestedAt: Long): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<BlocklistUpdateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                // A retry then skips the lists that succeeded since the request.
                .setInputData(workDataOf(KEY_REQUESTED_AT to requestedAt))
                .build()

        /** Lists last successful before this are fetched. */
        internal fun dueBefore(input: Data, now: Long): Long {
            val requestedAt = input.getLong(KEY_REQUESTED_AT, 0L)
            return if (requestedAt > 0) requestedAt else now - input.getInt(KEY_MAX_AGE_DAYS, 0) * DAY_MS
        }

        fun cancelUpdates(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(STALE_WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                BlocklistManager.initialize(applicationContext)
                when (val result = BlocklistManager.updateAllBlocklists(dueBefore(inputData, System.currentTimeMillis()))) {
                    is UpdateResult.Success -> {
                        // 0: nothing was due (a list that downloads is never empty).
                        if (result.totalEntries > 0) {
                            NotificationService(applicationContext).blocklistUpdated("All sources", result.totalEntries)
                        }
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
