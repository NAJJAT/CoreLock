package com.privacyguard.app.workers

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.privacyguard.app.FlavorRetention
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Deletes history past its retention period and caps the large tables. Runs from
 * WorkManager rather than the VPN service, so it also happens when the VPN never
 * stops (always-on) or never runs.
 *
 * The same worker, with [KEY_VACUUM], compacts the database weekly while the device
 * is charging and idle so deleted rows give their disk space back.
 */
class PruneWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "PruneWorker"
        private const val WORK_DAILY = "db_prune_daily"
        private const val WORK_NOW = "db_prune_now"
        private const val WORK_VACUUM = "db_vacuum_weekly"
        private const val KEY_VACUUM = "vacuum"

        private const val DAY_MS = 86_400_000L
        internal const val MAX_CONNECTIONS = 500_000
        internal const val MAX_DNS_QUERIES = 500_000
        // Bodies can be up to the MITM max payload size (32 KB by default).
        internal const val MAX_PAYLOADS = 5_000
        // Rows per delete transaction; small enough that ConnectionLogger's inserts
        // are not held off for long.
        internal const val BATCH_SIZE = 10_000
        /** Camera & mic history is kept 30 days whatever the general retention. */
        internal const val SENSOR_RETENTION_DAYS = 30

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(
                WORK_DAILY,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PruneWorker>(1, TimeUnit.DAYS).build(),
            )
            wm.enqueueUniquePeriodicWork(
                WORK_VACUUM,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PruneWorker>(7, TimeUnit.DAYS)
                    .setInputData(workDataOf(KEY_VACUUM to true))
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresCharging(true)
                            .setRequiresDeviceIdle(true)
                            .build()
                    )
                    .build(),
            )
        }

        /** Prunes as soon as possible, e.g. after MITM is turned off. */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NOW,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<PruneWorker>().build(),
            )
        }

        /**
         * Deletes history older than [retentionDays] and payload logs older than
         * [payloadCutoffMs], then trims each large table to its row cap.
         */
        internal suspend fun prune(
            db: AppDatabase,
            now: Long,
            retentionDays: Int,
            payloadCutoffMs: Long,
            maxConnections: Int = MAX_CONNECTIONS,
            maxDnsQueries: Int = MAX_DNS_QUERIES,
            maxPayloads: Int = MAX_PAYLOADS,
            batchSize: Int = BATCH_SIZE,
        ) {
            val cutoff = now - retentionDays.toLong() * DAY_MS

            val connections = db.connectionDao()
            deleteInBatches(batchSize) { connections.deleteOlderThanBatch(cutoff, it) }
            connections.capCutoff(maxConnections)?.let { cap ->
                deleteInBatches(batchSize) { connections.deleteOlderThanBatch(cap, it) }
            }

            val dnsQueries = db.dnsQueryDao()
            deleteInBatches(batchSize) { dnsQueries.deleteOlderThanBatch(cutoff, it) }
            dnsQueries.capCutoff(maxDnsQueries)?.let { cap ->
                deleteInBatches(batchSize) { dnsQueries.deleteOlderThanBatch(cap, it) }
            }

            val payloads = db.payloadLogDao()
            deleteInBatches(batchSize) { payloads.deleteOlderThanBatch(payloadCutoffMs, it) }
            payloads.capCutoff(maxPayloads)?.let { cap ->
                deleteInBatches(batchSize) { payloads.deleteOlderThanBatch(cap, it) }
            }

            // Small tables: one statement each is fine.
            db.tlsAlertDao().pruneOld(cutoff)
            db.dnsAnomalyDao().deleteOlderThan(cutoff)
            db.connectionProfileDao().deleteOlderThan(cutoff)
            db.sensorEventDao().deleteOlderThan(now - SENSOR_RETENTION_DAYS * DAY_MS)
        }

        private suspend fun deleteInBatches(batchSize: Int, deleteBatch: suspend (Int) -> Int) {
            while (deleteBatch(batchSize) >= batchSize) Unit
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val db = AppDatabase.getInstance(applicationContext)
            if (inputData.getBoolean(KEY_VACUUM, false)) {
                vacuum(db)
            } else {
                val retentionDays = SettingsPreferences.getInstance(applicationContext).retentionDays.value
                prune(
                    db,
                    now = System.currentTimeMillis(),
                    retentionDays = retentionDays,
                    payloadCutoffMs = FlavorRetention.payloadCutoffMs(applicationContext),
                )
                FlavorRetention.afterPrune(applicationContext)
                Log.d(TAG, "Prune complete (retention=$retentionDays days)")
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Typically SQLITE_BUSY while the VPN is writing; try again later.
            Log.w(TAG, "Database maintenance failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    /**
     * VACUUM rewrites the whole file, so it needs about twice the database size in
     * free space, and it blocks writers while it runs. ConnectionLogger drops a batch
     * whose insert fails, so connections logged during a VACUUM can be lost; that is
     * acceptable for history and why this only runs while charging and idle.
     */
    private fun vacuum(db: AppDatabase) {
        val dbFile = applicationContext.getDatabasePath(AppDatabase.DATABASE_NAME)
        if (dbFile.usableSpace < dbFile.length() * 2) {
            Log.w(TAG, "Skipping vacuum: ${dbFile.usableSpace} B free for a ${dbFile.length()} B database")
            return
        }
        db.openHelper.writableDatabase.execSQL("VACUUM")
        Log.d(TAG, "Vacuum complete")
    }
}
