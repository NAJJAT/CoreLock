package com.privacyguard.app.core.sensors

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.privacyguard.app.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Camera & Mic alerts. One notification per app (or per sensor for unidentified
 * apps): a repeat within [REPEAT_WINDOW_MS] updates it with a count instead of
 * buzzing again. Actions: review the app's permissions, "It was me" (teaches the
 * baseline), show the timeline.
 */
class SensorAlertNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    private data class Shown(val firstAt: Long, val count: Int, val useIds: Set<Long>, val severity: Severity)
    private val shown = HashMap<String, Shown>()

    fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Camera & microphone alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When the camera or microphone is used while your screen is off, at night, or by an app you did not open"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Shows or updates the alert for [useId]. Returns false when nothing was shown
     * (below HIGH, or already shown at this severity for this use).
     */
    @Synchronized
    fun show(
        useId: Long,
        rowId: Long,
        sensor: SensorType,
        packageName: String?,
        title: String,
        verdict: SensorVerdict,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (verdict.severity < Severity.HIGH) return false
        val tag = packageName ?: "unknown-${sensor.name}"
        val previous = shown[tag]?.takeIf { now - it.firstAt <= REPEAT_WINDOW_MS }
        if (previous != null && useId in previous.useIds && verdict.severity <= previous.severity) return false
        val repeat = previous != null && useId !in previous.useIds
        val state = Shown(
            firstAt = previous?.firstAt ?: now,
            count = (previous?.count ?: 0) + if (repeat || previous == null) 1 else 0,
            useIds = (previous?.useIds ?: emptySet()) + useId,
            severity = maxOf(previous?.severity ?: Severity.NONE, verdict.severity),
        )
        shown[tag] = state

        val text = if (state.count > 1) "${verdict.reason} (${state.count} times in the last 15 minutes)" else verdict.reason
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setGroup(GROUP)
            .setAutoCancel(true)
            // An update for the same use or a repeat is shown silently.
            .setOnlyAlertOnce(true)
            .setContentIntent(timelineIntent(rowId))
            .addAction(0, "Show timeline", timelineIntent(rowId))
        packageName?.let { builder.addAction(0, "Review permissions", permissionsIntent(it)) }
        builder.addAction(0, "It was me", itWasMeIntent(rowId, tag))

        return runCatching {
            manager.notify(tag, NOTIFICATION_ID, builder.build())
            true
        }.getOrDefault(false)  // notifications not permitted
    }

    private fun permissionsIntent(pkg: String): PendingIntent = PendingIntent.getActivity(
        context, pkg.hashCode(),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun timelineIntent(rowId: Long): PendingIntent = PendingIntent.getActivity(
        context, ("timeline$rowId").hashCode(),
        context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .putExtra(EXTRA_OPEN, OPEN_TIMELINE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun itWasMeIntent(rowId: Long, tag: String): PendingIntent = PendingIntent.getBroadcast(
        context, ("me$rowId").hashCode(),
        Intent(context, ItWasMeReceiver::class.java)
            .putExtra(EXTRA_ROW_ID, rowId)
            .putExtra(EXTRA_TAG, tag),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** "It was me": marks the use as expected (the app's baseline) and clears the alert. */
    class ItWasMeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val rowId = intent.getLongExtra(EXTRA_ROW_ID, -1)
            val tag = intent.getStringExtra(EXTRA_TAG)
            NotificationManagerCompat.from(context).cancel(tag, NOTIFICATION_ID)
            if (rowId < 0) return
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    AppDatabase.getInstance(context).sensorEventDao().markExpected(rowId)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "camera_mic_alerts"
        private const val GROUP = "camera_mic"
        private const val NOTIFICATION_ID = 7_301
        const val REPEAT_WINDOW_MS = 15 * 60_000L
        const val EXTRA_OPEN = "com.privacyguard.open"
        const val OPEN_TIMELINE = "sensorTimeline"
        private const val EXTRA_ROW_ID = "row_id"
        private const val EXTRA_TAG = "tag"
    }
}
