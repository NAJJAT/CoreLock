package com.privacyguard.app.flutter

import android.content.Context
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import com.privacyguard.app.data.repository.RulesRepo
import com.privacyguard.app.platform.android.PrivacyVpnService
import com.privacyguard.app.vpn.VpnManager
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterRule
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import com.privacyguard.app.core.stats.StatsManager
import java.util.UUID

/**
 * Bridge between the Flutter UI and the Android VPN engine.
 *
 * Register in MainActivity by calling [register] from [configureFlutterEngine].
 *
 * Method channel: "com.privacyguard/vpn"
 * Event channel:  "com.privacyguard/vpn_events" (fires every StatsManager snapshot update)
 */
class FlutterBridge(private val context: Context) {

    companion object {
        const val METHOD_CH = "com.privacyguard/vpn"
        const val EVENT_CH  = "com.privacyguard/vpn_events"
    }

    private val db       = AppDatabase.getInstance(context)
    private val prefs    = SettingsPreferences.getInstance(context)
    private val rulesRepo = RulesRepo(db.rulesDao(), FilterEngine())
    private val scope    = CoroutineScope(Dispatchers.IO)

    fun register(engine: FlutterEngine) {
        MethodChannel(engine.dartExecutor.binaryMessenger, METHOD_CH)
            .setMethodCallHandler { call, result ->
                scope.launch {
                    try {
                        handleMethod(call.method, call.arguments, result)
                    } catch (e: Exception) {
                        result.error("BRIDGE_ERROR", e.message, null)
                    }
                }
            }

        EventChannel(engine.dartExecutor.binaryMessenger, EVENT_CH)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(args: Any?, sink: EventChannel.EventSink) {
                    scope.launch {
                        StatsManager.snapshot.collectLatest { snap ->
                            val stats = buildStatsMap(snap)
                            sink.success(stats)
                        }
                    }
                }
                override fun onCancel(args: Any?) {}
            })
    }

    private suspend fun handleMethod(
        method: String,
        args: Any?,
        result: MethodChannel.Result,
    ) = when (method) {
        "startVpn" -> {
            VpnManager.startVpn(context)
            result.success(null)
        }
        "stopVpn" -> {
            VpnManager.stopVpn(context)
            result.success(null)
        }
        "isRunning" -> result.success(PrivacyVpnService.isRunning)

        "getStats" -> {
            val snap = StatsManager.snapshot.value
            result.success(buildStatsMap(snap))
        }

        "getTopRiskApps" -> {
            val since = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
            val conns = db.connectionDao().getRecentConnections(since, 500)
            val apps  = conns.groupBy { it.packageName }
                .filter { it.key.isNotBlank() }
                .map { (pkg, c) ->
                    mapOf(
                        "appName"         to (c.firstOrNull { it.appName.isNotBlank() }?.appName ?: pkg.substringAfterLast('.')),
                        "packageName"     to pkg,
                        "riskScore"       to (c.maxOfOrNull { riskScore(it.encryptionStatus, it.wasBackground) } ?: 0),
                        "grade"           to grade(c),
                        "cleartextCount"  to c.count { it.encryptionStatus == "CLEARTEXT" },
                        "backgroundCount" to c.count { it.wasBackground },
                    )
                }
                .sortedByDescending { (it["riskScore"] as Int) }
                .take(20)
            result.success(apps)
        }

        "getRecentConnections" -> {
            val limit = (args as? Map<*, *>)?.get("limit") as? Int ?: 50
            val since = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
            val conns = db.connectionDao().getRecentConnections(since, limit)
            result.success(conns.map { c ->
                mapOf(
                    "appName"       to c.appName,
                    "packageName"   to c.packageName,
                    "destination"   to (c.sniHostname ?: c.domain ?: c.destinationIp),
                    "encryption"    to c.encryptionStatus,
                    "isBlocked"     to c.wasBlocked,
                    "wasBackground" to c.wasBackground,
                    "timestamp"     to c.timestamp,
                )
            })
        }

        "getAlerts" -> {
            val limit = (args as? Map<*, *>)?.get("limit") as? Int ?: 50
            val dns = db.dnsAnomalyDao().recent(limit / 2).map { a ->
                mapOf("id" to "dns_${a.id}", "type" to a.anomalyType, "title" to a.anomalyType,
                      "detail" to "${a.domain} · ${a.description}", "severity" to a.severity,
                      "timestamp" to a.timestamp)
            }
            val tls = db.tlsAlertDao().recent(limit / 2).map { a ->
                mapOf("id" to "tls_${a.id}", "type" to a.alertType, "title" to (a.malwareName ?: a.alertType),
                      "detail" to (a.sni ?: ""), "severity" to a.severity,
                      "timestamp" to a.timestamp)
            }
            result.success((dns + tls).sortedByDescending { it["timestamp"] as Long })
        }

        "blockPackage" -> {
            val pkg = (args as? Map<*, *>)?.get("packageName") as? String ?: ""
            rulesRepo.upsertRule(FilterRule(
                id = "pkg:block:$pkg", label = "Block $pkg",
                action = FilterRule.Action.DENY, source = FilterRule.Source.USER,
                priority = FilterRule.HIGH_PRIORITY, matchPackage = pkg,
            ))
            result.success(null)
        }

        "unblockPackage" -> {
            val pkg = (args as? Map<*, *>)?.get("packageName") as? String ?: ""
            rulesRepo.deleteRule("pkg:block:$pkg")
            result.success(null)
        }

        "addDomainRule" -> {
            val m     = args as? Map<*, *>
            val domain = m?.get("domain") as? String ?: ""
            val block  = m?.get("block") as? Boolean ?: true
            rulesRepo.upsertRule(FilterRule(
                id = UUID.randomUUID().toString(),
                label = "${if (block) "Block" else "Allow"} $domain",
                action = if (block) FilterRule.Action.DENY else FilterRule.Action.ALLOW,
                source = FilterRule.Source.USER, priority = FilterRule.HIGH_PRIORITY,
                matchDomain = domain,
            ))
            result.success(null)
        }

        "setProtectionLevel" -> {
            val level = (args as? Map<*, *>)?.get("level") as? String ?: "STANDARD"
            prefs.setProtectionLevel(level)
            result.success(null)
        }

        else -> result.notImplemented()
    }

    private fun buildStatsMap(snap: com.privacyguard.app.core.stats.StatsSnapshot) = mapOf(
        "isRunning"             to PrivacyVpnService.isRunning,
        "trackersBlocked"       to snap.totalTrackersBlocked,
        "cleartextCount"        to 0,
        "privacyScore"          to 50,
        "activeConnections"     to snap.activeConnections.size,
        "throughputBytesPerSec" to snap.activeConnections.sumOf { it.bytesTransferred },
        "protectionLevel"       to prefs.protectionLevel.value,
    )

    private fun riskScore(enc: String, bg: Boolean): Int {
        var s = 0
        if (enc == "CLEARTEXT") s += 40
        if (enc == "WEAK_TLS")  s += 20
        if (bg) s += 15
        return s
    }

    private fun grade(conns: List<com.privacyguard.app.data.db.ConnectionEntity>): String {
        val clr = conns.count { it.encryptionStatus == "CLEARTEXT" }
        val bg  = conns.count { it.wasBackground }
        val s   = 100 - (clr * 5).coerceAtMost(40) - (bg * 2).coerceAtMost(20)
        return when {
            s >= 80 -> "A"
            s >= 60 -> "B"
            s >= 40 -> "C"
            s >= 20 -> "D"
            else    -> "F"
        }
    }
}
