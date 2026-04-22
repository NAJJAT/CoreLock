package com.privacyguard.app.service.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.privacyguard.app.core.stats.StatsManager

class NotificationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val snapshot = StatsManager.snapshot.value
        val topApps = snapshot.appStats
            .map { it.appName }
            .filter { it.isNotBlank() }
            .take(3)

        NotificationService(applicationContext).weeklyReport(
            blockedCount = snapshot.blockedToday.toInt(),
            topApps = topApps
        )
        return Result.success()
    }
}
