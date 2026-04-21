/**
 * NotificationHelper.kt
 * 
 * Manages notifications for PrivacyGuard
 * 
 * What it does:
 * =============
 * - Shows VPN active notification
 * - Shows block notifications (when an app is blocked)
 * - Shows weekly report notifications
 * - Manages notification channels
 * 
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.platform.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.privacyguard.app.MainActivity

/**
 * Manages all notifications for PrivacyGuard
 */
class NotificationHelper(private val context: Context) {
    
    companion object {
        private const val CHANNEL_VPN = "privacyguard_vpn"
        private const val CHANNEL_BLOCK = "privacyguard_block"
        private const val CHANNEL_REPORT = "privacyguard_report"
        
        private const val NOTIFICATION_VPN = 1
        private const val NOTIFICATION_BLOCK_BASE = 1000
    }
    
    /**
     * Creates all notification channels (call once on app start)
     */
    fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            
            // VPN channel (low importance, ongoing)
            val vpnChannel = NotificationChannel(
                CHANNEL_VPN,
                "PrivacyGuard VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when VPN is active"
                setShowBadge(false)
            }
            manager.createNotificationChannel(vpnChannel)
            
            // Block notifications channel (medium importance)
            val blockChannel = NotificationChannel(
                CHANNEL_BLOCK,
                "Block Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows when connections are blocked"
                setShowBadge(true)
            }
            manager.createNotificationChannel(blockChannel)
            
            // Weekly report channel (low importance)
            val reportChannel = NotificationChannel(
                CHANNEL_REPORT,
                "Weekly Reports",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Weekly privacy reports"
                setShowBadge(false)
            }
            manager.createNotificationChannel(reportChannel)
        }
    }
    
    /**
     * Shows VPN active notification (ongoing)
     */
    fun showVpnActiveNotification() {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_VPN)
            .setContentTitle("PrivacyGuard VPN")
            .setContentText("VPN is active - Your traffic is protected")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_VPN, notification)
    }
    
    /**
     * Shows VPN inactive notification
     */
    fun showVpnInactiveNotification() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_VPN)
    }
    
    /**
     * Shows block notification when an app is blocked
     */
    fun showBlockNotification(appName: String, domain: String, reason: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("open_tab", "connections")
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_BLOCK)
            .setContentTitle("Connection Blocked")
            .setContentText("$appName was blocked from connecting to $domain")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_BLOCK_BASE + System.currentTimeMillis().toInt(), notification)
    }
    
    /**
     * Shows weekly report notification
     */
    fun showWeeklyReportNotification(blockedCount: Int, topApps: List<String>) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("open_tab", "statistics")
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val topAppsText = topApps.take(3).joinToString(", ")
        val contentText = "Blocked $blockedCount trackers this week. Top apps: $topAppsText"
        
        val notification = NotificationCompat.Builder(context, CHANNEL_REPORT)
            .setContentTitle("PrivacyGuard Weekly Report")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_report_image)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(0, notification)
    }
    
    /**
     * Shows error notification
     */
    fun showErrorNotification(error: String) {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_VPN)
            .setContentTitle("PrivacyGuard Error")
            .setContentText(error)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(0, notification)
    }
}