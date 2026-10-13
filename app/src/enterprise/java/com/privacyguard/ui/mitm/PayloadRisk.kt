package com.privacyguard.ui.mitm

import com.privacyguard.app.data.db.NOT_DECRYPTED_PREFIX
import com.privacyguard.app.data.db.PayloadLogEntity
import com.privacyguard.app.data.db.PayloadLogSummary
import com.privacyguard.vpn.mitm.LeakDetector

/** What the risk rules read; built from a full entry or from a body-less summary. */
data class PayloadRiskInput(
    val method: String?,
    val headersJson: String,
    val piiRedacted: Boolean,
    val bodyLength: Int,
    /** A TLS session we only logged as metadata: there is no content to judge. */
    val metadataOnly: Boolean,
)

fun PayloadLogEntity.riskInput() = PayloadRiskInput(
    method, headers, piiRedacted, body?.length ?: 0,
    metadataOnly = !isMitmSuccess && bodyEncoding.startsWith(NOT_DECRYPTED_PREFIX),
)

fun PayloadLogSummary.riskInput() = PayloadRiskInput(
    method, headers, piiRedacted, bodyLength ?: 0,
    metadataOnly = !isMitmSuccess && bodyEncoding.startsWith(NOT_DECRYPTED_PREFIX),
)

/**
 * The one set of rules behind the Payload screen's dot colour, red stripe, "Flagged"
 * filter, risk ring and analytics, so a count and the list it describes agree.
 */
object PayloadRisk {

    /** At or above this an entry is flagged (as is any PII or personal-data leak). */
    const val FLAG_AT = 60
    /** At or above this (and not flagged) an entry is a warning. */
    const val WARN_AT = 30

    enum class Bucket { FLAGGED, WARNING, CLEAN, METADATA_ONLY }

    /** Header names, lower-cased. Matching names (not the raw JSON) avoids hits on values. */
    fun headerNames(json: String): Map<String, String> = runCatching {
        val obj = org.json.JSONObject(json)
        obj.keys().asSequence().associate { it.lowercase() to obj.optString(it) }
    }.getOrDefault(emptyMap())

    fun leaks(input: PayloadRiskInput): Set<LeakDetector.LeakType> =
        LeakDetector.decode(headerNames(input.headersJson)[LeakDetector.LEAKS_HEADER.lowercase()])

    fun score(input: PayloadRiskInput): Int {
        if (input.metadataOnly) return 0
        val names = headerNames(input.headersJson).keys
        var score = 0
        if (input.piiRedacted) score += 50
        score += 25 * leaks(input).size
        if (input.method.equals("POST", true) || input.method.equals("PUT", true)) score += 10
        if ("authorization" in names || "proxy-authorization" in names) score += 20
        if ("cookie" in names) score += 15
        if (names.any { it.startsWith("x-device") || it.contains("device-id") }) score += 15
        if (input.bodyLength > 500) score += 5
        return score.coerceIn(0, 99)
    }

    fun isFlagged(input: PayloadRiskInput): Boolean =
        !input.metadataOnly && (input.piiRedacted || leaks(input).isNotEmpty() || score(input) >= FLAG_AT)

    /** Every entry falls in exactly one bucket, so the buckets add up to the total. */
    fun bucket(input: PayloadRiskInput): Bucket = when {
        input.metadataOnly -> Bucket.METADATA_ONLY
        isFlagged(input) -> Bucket.FLAGGED
        score(input) >= WARN_AT -> Bucket.WARNING
        else -> Bucket.CLEAN
    }
}
