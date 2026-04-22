/**
 * WorkManagerInitializer.kt
 * 
 * Initializes all background workers on app start
 * 
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.workers

import android.content.Context
import androidx.startup.Initializer

/**
 * Initializes WorkManager workers on app startup
 */
class WorkManagerInitializer : Initializer<Unit> {
    
    companion object {
        private var isInitialized = false
        
        /**
         * Initializes all workers (can be called from Application class)
         */
        fun init(context: Context) {
            if (isInitialized) return
            isInitialized = true
            
            // Schedule weekly blocklist updates
            BlocklistUpdateWorker.schedulePeriodic(context)
            
            // Schedule weekly report generation
            WeeklyReportWorker.scheduleWeekly(context)
            
            android.util.Log.d("WorkManagerInitializer", "All workers initialized")
        }
    }
    
    override fun create(context: Context): Unit {
        init(context)
        return Unit
    }
    
    override fun dependencies(): List<Class<out Initializer<*>>> {
        return emptyList()
    }
}