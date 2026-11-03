package com.privacyguard.app.core.network

import android.content.Context
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.DnsAnomalyEntity
import com.privacyguard.app.data.db.NetworkTrustEntity

/**
 * Scores the current network and remembers it (Statistics › remembered networks). Home
 * did this inline in 1.0; the new Home and the old security overview both call this, so
 * networks are still recorded whichever screen is open.
 */
object NetworkTrustRecorder {

    suspend fun record(context: Context, db: AppDatabase, weekAnomalies: List<DnsAnomalyEntity>, at: Long): NetworkTrustSummary {
        val summary = NetworkTrustAnalyzer.summarize(context, weekAnomalies)
        db.networkTrustDao().upsert(
            NetworkTrustEntity(
                networkKey = summary.networkKey,
                networkLabel = summary.networkLabel,
                trustScore = summary.score,
                trustLevel = summary.level,
                openWifi = summary.openWifi,
                captivePortal = summary.captivePortal,
                tamperedAnswers = summary.tamperedAnswers,
                dnsHijacked = summary.dnsHijacked,
                tlsIntercepted = summary.tlsIntercepted,
                lastSeen = at,
            )
        )
        return summary
    }
}
