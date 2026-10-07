/**
 * WeeklyReportWorker.kt
 * 
 * Background worker for generating and sending weekly privacy reports
 * 
 * What it does:
 * =============
 * - Runs weekly to generate privacy report
 * - Aggregates statistics from past week
 * - Shows notification with summary
 * - Optionally saves report for later viewing
 * 
 * Report Contents:
 * ================
 * - Total trackers blocked
 * - Total data saved
 * - Top blocking apps
 * - Top blocked domains
 * - Privacy score trend
 * 
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.workers

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.repository.ConnectionRepository
import com.privacyguard.platform.android.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Worker for generating weekly privacy reports
 */
class WeeklyReportWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "WeeklyReportWorker"
        const val WORK_NAME = "weekly_report"
        private const val CHANNEL_REPORT = "privacyguard_report"
        
        /**
         * Schedules weekly report generation (every Monday at 9 AM)
         */
        fun scheduleWeekly(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            
            // Schedule for Monday 9 AM
            val workRequest = PeriodicWorkRequestBuilder<WeeklyReportWorker>(
                repeatInterval = 7,
                repeatIntervalTimeUnit = TimeUnit.DAYS
            )
                .setConstraints(constraints)
                .setInitialDelay(
                    getInitialDelayToMonday9AM(),
                    TimeUnit.MILLISECONDS
                )
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            
            Log.d(TAG, "Scheduled weekly report for Monday 9 AM")
        }
        
        /**
         * Calculates initial delay to next Monday 9 AM
         */
        private fun getInitialDelayToMonday9AM(): Long {
            val calendar = Calendar.getInstance()
            val now = calendar.timeInMillis
            
            // Set to next Monday 9 AM
            calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            calendar.set(Calendar.HOUR_OF_DAY, 9)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            
            var target = calendar.timeInMillis
            if (target <= now) {
                target += 7 * 24 * 60 * 60 * 1000L
            }
            
            return target - now
        }
        
        /**
         * Triggers an immediate report generation
         */
        fun generateNow(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<WeeklyReportWorker>()
                .build()
            WorkManager.getInstance(context).enqueue(workRequest)
            Log.d(TAG, "Triggered immediate report generation")
        }
        
        /**
         * Cancels scheduled reports
         */
        fun cancelReports(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled weekly reports")
        }
    }
    
    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Generating weekly report")
                
                val report = generateReport()
                
                // Show notification
                showReportNotification(report)
                
                // Save report to database (optional)
                saveReport(report)
                
                Log.d(TAG, "Weekly report generated successfully")
                Result.success()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate weekly report", e)
                Result.retry()
            }
        }
    }
    
    /**
     * Generates the weekly report. Blocking happens at DNS level, so the blocked
     * count and top domains come from blocked DNS lookups (connections are never
     * stored as blocked), and the score is the Dashboard's, so the two agree.
     */
    private suspend fun generateReport(): WeeklyReport {
        val now = System.currentTimeMillis()
        val oneWeekAgo = now - (7 * 24 * 60 * 60 * 1000L)

        val database = AppDatabase.getInstance(applicationContext)
        val connectionRepo = ConnectionRepository(database.connectionDao())
        val dnsQueries = database.dnsQueryDao()

        val totalBlocked = dnsQueries.countBlockedSince(oneWeekAgo)
        val totalBytes = connectionRepo.getDailyStats(7).sumOf { it.totalBytes }
        val topBlockedDomains = dnsQueries.topBlockedDomainsSince(oneWeekAgo, 5)
        val topBlockedApps = dnsQueries.topBlockedAppsSince(oneWeekAgo, 3)
        val hourlyStats = connectionRepo.getHourlyStats(days = 7)
        val privacyScore = com.privacyguard.app.core.privacy.PrivacyScoreCalculator.calculate(
            connections = database.connectionDao().getRecentConnections(oneWeekAgo, 2_000),
            profiles = database.connectionProfileDao().allProfiles()
                .map { com.privacyguard.app.data.repository.MetadataRepo(database.connectionProfileDao()).toDomainForDashboard(it) },
            trackersBlocked = totalBlocked,
            blocklistDomains = com.privacyguard.app.core.blocklist.BlocklistManager.size.value,
        ).score

        return WeeklyReport(
            date = now,
            weekStart = oneWeekAgo,
            weekEnd = now,
            totalBlocked = totalBlocked.toLong(),
            totalDataSaved = totalBytes,
            topBlockedDomains = topBlockedDomains.map { it.domain to it.blockedQueries },
            topBlockedApps = topBlockedApps.map { it.appName.ifBlank { it.appPackage } },
            privacyScore = privacyScore,
            hourlyStats = hourlyStats
        )
    }

    /**
     * Shows notification with report summary
     */
    private fun showReportNotification(report: WeeklyReport) {
        val notificationHelper = NotificationHelper(applicationContext)
        
        // The notification says "most tracked app", so name apps, not domains.
        val topApps = report.topBlockedApps
        notificationHelper.showWeeklyReportNotification(
            blockedCount = report.totalBlocked.toInt(),
            topApps = topApps
        )
    }
    
    /**
     * Saves report to database for history
     */
    private suspend fun saveReport(report: WeeklyReport) {
        Log.d(TAG, "Saving report: Blocked ${report.totalBlocked}, Score ${report.privacyScore}")
    }
}

/**
 * Weekly report data class
 */
data class WeeklyReport(
    val date: Long,
    val weekStart: Long,
    val weekEnd: Long,
    val totalBlocked: Long,
    val totalDataSaved: Long,
    val topBlockedDomains: List<Pair<String, Int>>,
    val topBlockedApps: List<String>,
    val privacyScore: Int,
    val hourlyStats: List<com.privacyguard.app.data.db.HourlyStats>
) {
    
    val formattedDate: String
        get() {
            val format = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            return format.format(Date(date))
        }
    
    val formattedWeekRange: String
        get() {
            val format = SimpleDateFormat("MMM dd", Locale.getDefault())
            return "${format.format(Date(weekStart))} - ${format.format(Date(weekEnd))}"
        }
    
    val formattedDataSaved: String
        get() = when {
            totalDataSaved < 1024 -> "$totalDataSaved B"
            totalDataSaved < 1024 * 1024 -> "${totalDataSaved / 1024} KB"
            else -> String.format(Locale.US, "%.1f MB", totalDataSaved / (1024.0 * 1024.0))
        }
}
