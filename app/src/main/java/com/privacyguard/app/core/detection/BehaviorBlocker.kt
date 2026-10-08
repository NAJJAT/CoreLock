package com.privacyguard.app.core.detection

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.privacyguard.app.core.sensors.SensorPurpose
import com.privacyguard.app.core.tracker.DestinationOwner
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConfigDatabase
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.db.SensorEventEntity
import com.privacyguard.app.data.repository.RulesRepo
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Turns an app's risk score into a network block, by protection level:
 * MINIMAL ignores the score, STANDARD blocks from 70, STRICT from 40. The level
 * test lives in [FilterEngine] through the rule's source ([FilterRule.Source.BEHAVIOR]
 * for 70+, [FilterRule.Source.BEHAVIOR_STRICT] for 40–69), so changing the level
 * needs no re-scoring.
 *
 * Widely used apps ([TOP_APPS]) are never blocked on behavior alone — the user
 * gets an alert instead — and an app the user unblocked is never blocked again
 * by behavior.
 */
class BehaviorBlocker(context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val configDb = ConfigDatabase.getInstance(appContext)
    private val rulesRepo = RulesRepo(configDb.rulesDao(), FilterEngine())
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Re-scores [packageName] and adds, changes or removes its behavior rule. */
    suspend fun review(packageName: String, now: Long = System.currentTimeMillis()) {
        if (packageName == appContext.packageName) return
        val risk = AppRiskScorer.score(inputsFor(appContext, db, packageName, now))
        val source = when {
            risk.score >= AppRiskScorer.HIGH -> FilterRule.Source.BEHAVIOR
            risk.score >= AppRiskScorer.MEDIUM -> FilterRule.Source.BEHAVIOR_STRICT
            else -> null
        }
        val ruleId = ruleId(packageName)
        val existing = configDb.rulesDao().getAllRules().firstOrNull { it.id == ruleId }

        if (source == null || packageName in userOverrides()) {
            if (existing != null) rulesRepo.deleteRule(ruleId)
            return
        }
        if (isTopApp(packageName)) {
            notifyNotBlocked(packageName, risk)
            return
        }
        if (existing?.type == source.name) return
        rulesRepo.upsertRule(
            FilterRule(
                id = ruleId,
                label = "Behavior: ${risk.reasons.firstOrNull() ?: "risk score ${risk.score}"}",
                action = FilterRule.Action.DENY,
                source = source,
                priority = FilterRule.HIGH_PRIORITY,
                matchPackage = packageName,
            )
        )
        Log.i(TAG, "$packageName scored ${risk.score} — behavior rule ${source.name}")
        if (existing == null) notifyBlocked(packageName, risk, source)
    }

    private fun userOverrides(): Set<String> = prefs.getStringSet(KEY_OVERRIDES, null).orEmpty()

    private fun isTopApp(pkg: String): Boolean {
        if (TOP_APPS.any { pkg == it || pkg.startsWith("$it.") }) return true
        val info = runCatching { appContext.packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: return false
        return info.flags and ApplicationInfo.FLAG_SYSTEM != 0
    }

    private fun label(pkg: String): String = runCatching {
        val pm = appContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Automatic blocks", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When CoreLock blocks an app's internet access because of its behavior"
            }
        )
    }

    private fun notifyBlocked(pkg: String, risk: AppRisk, source: FilterRule.Source) {
        ensureChannel()
        val name = label(pkg)
        val levelText = if (source == FilterRule.Source.BEHAVIOR) "Standard and Strict protection" else "Strict protection"
        val text = "$name's internet access is blocked: ${risk.reasons.first().replaceFirstChar { it.lowercase() }}. " +
            "(Risk score ${risk.score}, applies at $levelText.)"
        val undo = PendingIntent.getBroadcast(
            appContext, pkg.hashCode(),
            Intent(appContext, UndoReceiver::class.java).putExtra(EXTRA_PACKAGE, pkg),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(pkg, NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("$name blocked")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Undo", undo))
    }

    private fun notifyNotBlocked(pkg: String, risk: AppRisk) {
        // Only when it would have been blocked at the strictest level.
        if (risk.score < AppRiskScorer.MEDIUM) return
        ensureChannel()
        val name = label(pkg)
        val text = "$name looks risky (score ${risk.score}): ${risk.reasons.first().replaceFirstChar { it.lowercase() }}. " +
            "It is a widely used app, so CoreLock did not block it — review it in Apps."
        post(pkg, NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Review $name")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true))
    }

    private fun post(tag: String, builder: NotificationCompat.Builder) {
        runCatching { NotificationManagerCompat.from(appContext).notify(tag, NOTIFICATION_ID, builder.setAutoCancel(true).build()) }
    }

    /** "Undo": removes the behavior block and never blocks this app on behavior again. */
    class UndoReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
            NotificationManagerCompat.from(context).cancel(pkg, NOTIFICATION_ID)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putStringSet(KEY_OVERRIDES, prefs.getStringSet(KEY_OVERRIDES, null).orEmpty() + pkg).apply()
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val config = ConfigDatabase.getInstance(context)
                    RulesRepo(config.rulesDao(), FilterEngine()).deleteRule(ruleId(pkg))
                } finally {
                    pending.finish()
                }
            }
        }
    }

    companion object {
        private const val TAG = "BehaviorBlocker"
        private const val PREFS = "behavior_blocker"
        private const val KEY_OVERRIDES = "never_block"
        private const val CHANNEL_ID = "behavior_blocks"
        private const val NOTIFICATION_ID = 7_302
        private const val EXTRA_PACKAGE = "package"
        private const val WINDOW_MS = 7L * 24 * 60 * 60 * 1000

        fun ruleId(pkg: String) = "behavior:block:$pkg"

        /** The user unblocked [pkg]: never block it on behavior again. */
        fun neverBlock(context: Context, pkg: String) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putStringSet(KEY_OVERRIDES, prefs.getStringSet(KEY_OVERRIDES, null).orEmpty() + pkg).apply()
        }

        /** Never auto-blocked on behavior; the user is alerted instead. */
        val TOP_APPS = listOf(
            "com.whatsapp", "org.telegram.messenger", "org.thoughtcrime.securesms", "com.facebook.katana",
            "com.facebook.orca", "com.instagram.android", "com.zhiliaoapp.musically", "com.snapchat.android",
            "com.google.android.youtube", "com.android.chrome", "com.google.android.gm", "com.google.android.apps.maps",
            "com.spotify.music", "com.netflix.mediaclient", "us.zoom.videomeetings", "com.microsoft.teams",
            "com.skype.raider", "com.discord", "com.twitter.android", "com.amazon.mShop.android.shopping",
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.google.android.dialer",
        )

        /** Everything the score needs for one app, from the history database. */
        suspend fun inputsFor(context: Context, db: AppDatabase, pkg: String, now: Long): AppRiskInputs {
            val since = now - WINDOW_MS
            val events: List<SensorEventEntity> = db.sensorEventDao().forPackage(pkg, since)
            val connections: List<ConnectionEntity> = db.connectionDao().getRecentConnections(now - 24L * 60 * 60 * 1000, 5_000)
                .filter { it.packageName == pkg }
            val purpose = runCatching {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                val category = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) info.category else -1
                SensorPurpose.of(pkg, category, info.flags and ApplicationInfo.FLAG_SYSTEM != 0)
            }.getOrDefault(SensorPurpose.UNKNOWN)
            val stalkerware = runCatching {
                @Suppress("DEPRECATION")
                val perms = context.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                    .requestedPermissions.orEmpty().toSet()
                val details = com.privacyguard.app.core.apps.InstalledAppsCache.details(context, pkg)
                StalkerwareDetector.assess(perms, emptyList(), details.hasLauncherIcon, details.installerPackage, details.isSystemApp).score
            }.getOrDefault(0)
            return inputsFrom(pkg, connections, events, purpose, stalkerware)
        }

        /** Pure part of [inputsFor], shared with the Apps screen. */
        fun inputsFrom(
            pkg: String,
            connections: List<ConnectionEntity>,
            events: List<SensorEventEntity>,
            purpose: SensorPurpose,
            stalkerwareScore: Int,
        ): AppRiskInputs {
            val trackers = connections
                .map { (it.sniHostname ?: it.domain) to it.destinationIp }
                .distinct()
                .count { (host, ip) -> DestinationOwner.describe(host, ip, pkg).role == DestinationOwner.Role.TRACKER }
            return AppRiskInputs(
                trackerDestinations = trackers,
                cleartextConnections = connections.count { it.encryptionStatus == "CLEARTEXT" },
                weakTlsConnections = connections.count { it.encryptionStatus == "WEAK_TLS" },
                stalkerwareScore = stalkerwareScore,
                sensors = SensorRiskSummary.from(events, purpose),
            )
        }
    }
}
