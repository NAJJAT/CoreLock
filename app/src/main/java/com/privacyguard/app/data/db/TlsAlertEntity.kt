package com.privacyguard.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tls_alerts")
data class TlsAlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val alertType: String,   // "JA3_THREAT" | "CT_NEW_CERT" | "WEAK_CIPHER" | TYPE_TLS_FP_CHANGE
    val hash: String? = null,      // JA3 hash, or the JA4 fingerprint for TYPE_TLS_FP_CHANGE
    val ja3String: String? = null,
    val malwareName: String? = null,
    val category: String? = null,
    val severity: Int = 5,
    val sni: String? = null,
    val packageName: String? = null,
    val detail: String? = null,
) {
    companion object {
        /** An app used a TLS fingerprint outside its learned baseline, without an update. */
        const val TYPE_TLS_FP_CHANGE = "TLS_FP_CHANGE"
    }
}
