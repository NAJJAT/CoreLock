package com.privacyguard.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Learning state per app for the TLS fingerprint baseline. */
@Entity(tableName = "tls_app_profiles", primaryKeys = ["uid", "packageName"])
data class TlsAppProfileEntity(
    val uid: Int,
    val packageName: String,
    val learningStartedAt: Long,
    val envSignature: String,
    val versionCode: Long,
    val lastUpdateAt: Long?,
)

/**
 * One fingerprint an app has used. Rows with origin ALERTED are the ones the
 * user was warned about; USER_TRUSTED is an alert the user accepted.
 */
@Entity(
    tableName = "tls_fingerprints",
    indices = [Index(value = ["uid", "packageName", "ja4"], unique = true)],
)
data class TlsFingerprintEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: Int,
    val packageName: String,
    val ja4: String,
    val ja3: String,
    val firstSni: String?,
    val firstSeen: Long,
    val lastSeen: Long,
    val seenCount: Long,
    val origin: String,
    val versionCode: Long,
)
