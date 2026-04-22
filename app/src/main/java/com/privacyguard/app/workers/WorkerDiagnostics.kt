/**
 * WorkerDiagnostics.kt
 * 
 * Diagnostics and monitoring for background workers
 * 
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.workers

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.guava.await

/**
 * Provides diagnostics for background workers
 */
class WorkerDiagnostics(private val context: Context) {
    
    /**
     * Gets status of all workers
     */
    suspend fun getAllWorkerStatuses(): Map<String, WorkerStatus> {
        val workManager = WorkManager.getInstance(context)
        
        val blocklistStatus = getWorkerStatus(workManager, BlocklistUpdateWorker.WORK_NAME)
        val reportStatus = getWorkerStatus(workManager, WeeklyReportWorker.WORK_NAME)
        
        return mapOf(
            "blocklist_update" to blocklistStatus,
            "weekly_report" to reportStatus
        )
    }
    
    private suspend fun getWorkerStatus(workManager: WorkManager, workName: String): WorkerStatus {
        return try {
            val workInfos = workManager.getWorkInfosForUniqueWork(workName).await()
            val lastWorkInfo = workInfos.lastOrNull()
            
            WorkerStatus(
                isEnqueued = workInfos.isNotEmpty(),
                lastState = lastWorkInfo?.state?.name ?: "UNKNOWN",
                lastRunTime = lastWorkInfo?.outputData?.getLong("timestamp", 0) ?: 0,
                lastSuccess = lastWorkInfo?.state == WorkInfo.State.SUCCEEDED
            )
        } catch (e: Exception) {
            WorkerStatus(false, "ERROR", 0, false)
        }
    }
    
    /**
     * Triggers manual update of all workers (for testing)
     */
    suspend fun triggerAllWorkers(): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()
        
        try {
            BlocklistUpdateWorker.updateNow(context)
            results["blocklist_update"] = true
        } catch (e: Exception) {
            results["blocklist_update"] = false
        }
        
        try {
            WeeklyReportWorker.generateNow(context)
            results["weekly_report"] = true
        } catch (e: Exception) {
            results["weekly_report"] = false
        }
        
        return results
    }
    
    /**
     * Resets all workers (cancels and reschedules)
     */
    suspend fun resetAllWorkers() {
        val workManager = WorkManager.getInstance(context)
        
        // Cancel all existing work
        workManager.cancelAllWork()
        
        // Reschedule
        BlocklistUpdateWorker.schedulePeriodic(context)
        WeeklyReportWorker.scheduleWeekly(context)
    }
}

/**
 * Status of a worker
 */
data class WorkerStatus(
    val isEnqueued: Boolean,
    val lastState: String,
    val lastRunTime: Long,
    val lastSuccess: Boolean
)
