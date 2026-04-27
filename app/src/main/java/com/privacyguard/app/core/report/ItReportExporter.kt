package com.privacyguard.app.core.report

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.privacyguard.app.core.behavior.BehaviorDnaAnalyzer
import com.privacyguard.app.core.privacy.PrivacyScoreCalculator
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.db.DnsAnomalyEntity
import com.privacyguard.core.metadata.ConnectionProfile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class ItReportFiles(
    val jsonFile: File,
    val pdfFile: File,
)

object ItReportExporter {
    fun export(
        context: Context,
        connections: List<ConnectionEntity>,
        anomalies: List<DnsAnomalyEntity>,
        profiles: List<ConnectionProfile>,
        trackersBlocked: Int,
        blocklistDomains: Int,
    ): ItReportFiles {
        val outputDir = File(context.filesDir, "reports").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val jsonFile = File(outputDir, "privacyguard-report-$stamp.json")
        val pdfFile = File(outputDir, "privacyguard-report-$stamp.pdf")
        val behavior = BehaviorDnaAnalyzer.summarizeAll(profiles)
        val privacyScore = PrivacyScoreCalculator.calculate(connections, profiles, trackersBlocked, blocklistDomains)

        val backgroundCount = connections.count { it.wasBackground }
        val cleartextCount = connections.count {
            !it.wasBlocked && (it.encryptionStatus == "CLEARTEXT" || it.encryptionStatus == "UNKNOWN")
        }
        val json = JSONObject().apply {
            put("generatedAt", stamp)
            put("privacyScore", privacyScore.score)
            put("encryptedRatio", privacyScore.encryptedRatio)
            put("backgroundRatio", privacyScore.backgroundRatio)
            put("summary", JSONObject().apply {
                put("connections", connections.size)
                put("blocked", connections.count { it.wasBlocked })
                put("cleartext", cleartextCount)
                put("background", backgroundCount)
                put("anomalies", anomalies.size)
                put("behaviorAlerts", behavior.sumOf { it.findings.size })
            })
            put("connections", JSONArray().apply {
                connections.take(200).forEach { connection ->
                    put(JSONObject().apply {
                        put("appName", connection.appName)
                        put("packageName", connection.packageName)
                        put("domain", connection.domain ?: connection.destinationIp)
                        put("destinationIp", connection.destinationIp)
                        put("port", connection.destinationPort)
                        put("protocol", connection.protocol)
                        put("blocked", connection.wasBlocked)
                        put("background", connection.wasBackground)
                        put("encryptionStatus", connection.encryptionStatus)
                        put("tlsVersion", connection.tlsVersion ?: "")
                        put("bytesSent", connection.bytesSent)
                        put("bytesReceived", connection.bytesReceived)
                        put("timestamp", connection.timestamp)
                    })
                }
            })
            put("anomalies", JSONArray().apply {
                anomalies.take(100).forEach { anomaly ->
                    put(JSONObject().apply {
                        put("packageName", anomaly.packageName)
                        put("domain", anomaly.domain)
                        put("type", anomaly.anomalyType)
                        put("description", anomaly.description)
                        put("severity", anomaly.severity)
                        put("timestamp", anomaly.timestamp)
                    })
                }
            })
        }
        jsonFile.writeText(json.toString(2))
        exportPdf(pdfFile, privacyScore.score, connections, anomalies, behavior, backgroundCount, cleartextCount)
        return ItReportFiles(jsonFile, pdfFile)
    }

    private fun exportPdf(
        file: File,
        privacyScore: Int,
        connections: List<ConnectionEntity>,
        anomalies: List<DnsAnomalyEntity>,
        behavior: List<com.privacyguard.app.core.behavior.AppBehaviorSummary>,
        backgroundCount: Int = 0,
        cleartextCount: Int = 0,
    ) {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        val titlePaint = Paint().apply { textSize = 20f; isFakeBoldText = true }
        val bodyPaint = Paint().apply { textSize = 12f }
        var y = 40f
        canvas.drawText("PrivacyGuard Share with IT Report", 36f, y, titlePaint)
        y += 28f
        canvas.drawText("Privacy Score: $privacyScore/100", 36f, y, bodyPaint)
        y += 20f
        canvas.drawText("Connections: ${connections.size}  Blocked: ${connections.count { it.wasBlocked }}  Cleartext: $cleartextCount  Background: $backgroundCount  Anomalies: ${anomalies.size}", 36f, y, bodyPaint)
        y += 24f
        canvas.drawText("Top anomalies", 36f, y, titlePaint)
        y += 20f
        anomalies.take(8).forEach { anomaly ->
            canvas.drawText("- ${anomaly.domain} · ${anomaly.anomalyType} · sev ${anomaly.severity}", 36f, y, bodyPaint)
            y += 16f
        }
        y += 12f
        canvas.drawText("Behavior findings", 36f, y, titlePaint)
        y += 20f
        behavior.flatMap { it.findings }.take(8).forEach { finding ->
            canvas.drawText("- ${finding.packageName}: ${finding.title}", 36f, y, bodyPaint)
            y += 16f
        }
        document.finishPage(page)
        file.outputStream().use { document.writeTo(it) }
        document.close()
    }
}
