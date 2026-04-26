package com.privacyguard.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "payload_logs",
    indices = [
        Index("sessionId"),
        Index("timestamp"),
        Index("ownerPackage")
    ]
)
data class PayloadLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val sessionId: String,
    val direction: String,
    val ownerPackage: String?,
    val sniHostname: String?,
    val destinationIp: String,
    val destinationPort: Int,
    val protocol: String,
    val method: String?,
    val urlPath: String?,
    val headers: String,
    val body: String?,
    val bodyEncoding: String,
    val sizeBytes: Int,
    val piiRedacted: Boolean,
    val isMitmSuccess: Boolean
)