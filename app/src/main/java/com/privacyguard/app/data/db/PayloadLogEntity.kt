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

/** [PayloadLogEntity.bodyEncoding] prefix for TLS sessions logged as metadata only. */
const val NOT_DECRYPTED_PREFIX = "not-decrypted:"

/**
 * Why this entry has no readable payload, or null when there is nothing to explain
 * (decrypted traffic, or plain HTTP). A decrypted request with an empty body — e.g. a
 * GET — is NOT an encryption problem.
 */
fun PayloadLogEntity.notDecryptedReason(): String? {
    if (isMitmSuccess || destinationPort != 443) return null
    return when (bodyEncoding.removePrefix(NOT_DECRYPTED_PREFIX)) {
        "mitm-off" -> "HTTPS interception is turned off or consent has expired, so this connection was passed through untouched."
        "bypass" -> "This host is on the never-intercept list (DNS-over-HTTPS / Google / Samsung services). Decrypting it breaks name resolution or sign-in, so it is passed through."
        "pinned" -> "This app pins its certificate or does not trust user-installed CAs (the default for apps targeting Android 7+). It rejected the PrivacyGuard certificate, so the connection was passed through and only metadata is visible."
        "failed" -> "Interception could not start for this connection (CA not ready yet or local proxy error). It was passed through; later connections to this host may be decrypted."
        else -> "This connection was not intercepted. Make sure the PrivacyGuard CA is installed and HTTPS interception is on."
    }
}
