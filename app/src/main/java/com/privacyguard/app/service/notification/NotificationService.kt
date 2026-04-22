package com.privacyguard.app.service.notification

import android.content.Context
import com.privacyguard.app.core.utils.NotificationHelper
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import com.privacyguard.app.data.repository.NotificationRepository
import com.privacyguard.app.domain.model.Notification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationService(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = SettingsPreferences.getInstance(appContext)
    private val repository = NotificationRepository.getInstance()

    fun sendNotification(notification: Notification) {
        if (!prefs.shouldShowNotification(notification.type)) return

        CoroutineScope(Dispatchers.IO).launch {
            repository.addNotification(notification)
        }

        NotificationHelper.showNotification(appContext, notification)
    }

    fun vpnStarted() = sendNotification(Notification.vpnStarted())

    fun vpnStopped() = sendNotification(Notification.vpnStopped())

    fun connectionBlocked(appName: String, domain: String) =
        sendNotification(Notification.connectionBlocked(appName, domain))

    fun trackerBlocked(trackerName: String, appName: String) =
        sendNotification(Notification.trackerBlocked(trackerName, appName))

    fun appBlocked(appName: String) = sendNotification(Notification.appBlocked(appName))

    fun killSwitchActivated() = sendNotification(Notification.killSwitchActivated())

    fun weeklyReport(blockedCount: Int, topApps: List<String>) =
        sendNotification(Notification.weeklyReport(blockedCount, topApps))

    fun blocklistUpdated(source: String, count: Int) =
        sendNotification(Notification.blocklistUpdated(source, count))
}
