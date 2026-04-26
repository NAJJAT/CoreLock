package com.privacyguard.app.vpn.mitm

import android.content.pm.PackageManager
import android.webkit.JavascriptInterface
import com.privacyguard.app.data.db.PayloadLogDao
import com.privacyguard.app.data.db.PayloadLogEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PayloadInspectorBridge(
    private val dao: PayloadLogDao,
    private val packageManager: PackageManager,
) {
    private val timeFmt = SimpleDateFormat("H:mm:ss", Locale.getDefault())

    @JavascriptInterface
    fun getRequests(): String = runBlocking {
        val logs = dao.recentLogs(200).first()
        val arr = JSONArray()
        logs.forEach { arr.put(entityToJson(it)) }
        arr.toString()
    }

    @JavascriptInterface
    fun getStats(): String = runBlocking {
        val logs = dao.recentLogs(200).first()
        val flagged = logs.count { it.piiRedacted || riskScore(it) >= 70 }
        val totalBytes = logs.sumOf { it.sizeBytes }
        JSONObject().apply {
            put("total", logs.size)
            put("flagged", flagged)
            put("warnings", logs.count { val r = riskScore(it); r in 50..69 })
            put("clean", logs.count { riskScore(it) < 30 })
            put("totalKB", totalBytes / 1024.0)
        }.toString()
    }

    @JavascriptInterface
    fun blockDomain(domain: String) {
        // Stub — wire to DomainFilter when needed
    }

    fun entityToJson(e: PayloadLogEntity): JSONObject {
        val appLabel = e.ownerPackage?.let { pkg ->
            try {
                val ai = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(ai).toString()
            } catch (_: Exception) { pkg.substringAfterLast('.') }
        } ?: "Unknown"

        val host = e.sniHostname ?: e.destinationIp
        val path = e.urlPath ?: "/"
        val risk = riskScore(e)

        val sizeStr = when {
            e.sizeBytes < 1024 -> "${e.sizeBytes} B"
            e.sizeBytes < 1_048_576 -> "${e.sizeBytes / 1024} KB"
            else -> String.format("%.1f MB", e.sizeBytes / 1_048_576f)
        }

        // Parse stored headers JSON (Map<String,String>) → [[name,value] | [name,value,flag], ...]
        val reqHdrs = JSONArray()
        try {
            val map = JSONObject(e.headers)
            map.keys().forEach { k ->
                val v = map.getString(k)
                val flag = when (k.lowercase()) {
                    "authorization", "x-api-key", "cookie", "set-cookie" -> "warn"
                    "x-forwarded-for", "x-real-ip" -> "bad"
                    else -> null
                }
                val row = JSONArray().put(k).put(v)
                if (flag != null) row.put(flag)
                reqHdrs.put(row)
            }
        } catch (_: Exception) {}

        // Try to parse body as JSON object/array; fall back to raw string
        val bodyParsed: Any? = e.body?.let { raw ->
            try { JSONObject(raw) } catch (_: Exception) {
                try { JSONArray(raw) } catch (_: Exception) { raw }
            }
        }

        return JSONObject().apply {
            put("id", e.id)
            put("app", appLabel)
            put("method", e.method ?: if (e.direction == "OUTBOUND") "POST" else "GET")
            put("host", host)
            put("path", path)
            put("status", 200)
            put("size", sizeStr)
            put("time", timeFmt.format(Date(e.timestamp)))
            put("flagged", e.piiRedacted || risk >= 70)
            put("riskScore", risk)
            put("latency", "—")
            put("protocol", e.protocol)
            put("tls", if (e.destinationPort == 443) "TLS" else "—")
            if (e.direction == "OUTBOUND") {
                put("requestPayload", bodyParsed ?: JSONObject.NULL)
                put("responsePayload", JSONObject.NULL)
            } else {
                put("requestPayload", JSONObject.NULL)
                put("responsePayload", bodyParsed ?: JSONObject.NULL)
            }
            put("reqHeaders", reqHdrs)
            put("resHeaders", JSONArray())
            put("risks", buildRisks(e, host, risk))
            put("timeline", JSONArray())
            put("fields", buildFields(e))
        }
    }

    private fun riskScore(e: PayloadLogEntity): Int {
        var score = 0
        if (e.piiRedacted) score += 50
        if (e.destinationPort == 80) score += 30
        if (e.sizeBytes > 500_000) score += 15
        val suspiciousKeywords = listOf(
            "analytics", "tracking", "telemetry", "collect", "metrics", "geoip", "maxmind"
        )
        if (suspiciousKeywords.any { e.sniHostname?.contains(it, ignoreCase = true) == true }) score += 25
        return score.coerceIn(0, 100)
    }

    private fun buildRisks(e: PayloadLogEntity, host: String, risk: Int): JSONArray {
        val arr = JSONArray()
        if (e.piiRedacted) arr.put(JSONObject().apply {
            put("level", "crit")
            put("title", "PII detected and redacted")
            put("body", "Personal data was found in this payload and masked before storage.")
        })
        if (e.destinationPort == 80) arr.put(JSONObject().apply {
            put("level", "crit")
            put("title", "Unencrypted HTTP traffic")
            put("body", "Traffic to $host on port 80 is transmitted without encryption.")
        })
        if (e.sizeBytes > 500_000) arr.put(JSONObject().apply {
            put("level", "warn")
            put("title", "Large payload (${e.sizeBytes / 1024} KB)")
            put("body", "Unusually large upload detected for $host.")
        })
        if (arr.length() == 0) arr.put(JSONObject().apply {
            put("level", "info")
            put("title", "No anomalies detected")
            put("body", "Traffic to $host appears normal.")
        })
        return arr
    }

    private fun buildFields(e: PayloadLogEntity): JSONArray {
        val arr = JSONArray()
        e.ownerPackage?.let { pkg ->
            arr.put(JSONObject().apply {
                put("key", "ownerPackage")
                put("val", pkg)
                put("dot", "var(--blue)")
                put("flag", JSONObject.NULL)
            })
        }
        if (e.destinationPort != 443 && e.destinationPort != 80) {
            arr.put(JSONObject().apply {
                put("key", "destinationPort")
                put("val", e.destinationPort.toString())
                put("dot", "var(--amber)")
                put("flag", "NON-STANDARD")
                put("fc", "var(--amber)")
                put("fb", "var(--amber-dim)")
            })
        }
        return arr
    }
}
