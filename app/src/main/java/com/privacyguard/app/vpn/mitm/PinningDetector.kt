package com.privacyguard.vpn.mitm

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Certificate Pinning Detector
 *
 * Identifies domains and applications that use certificate pinning to prevent MITM
 * interception. Maintains both static hardcoded lists and dynamic runtime detection.
 *
 * Business Reason: Prevents the MITM engine from breaking apps that implement
 * certificate pinning, which would cause connection failures for the user.
 *
 * Thread Safety: All methods are thread-safe using ConcurrentHashMap for dynamic pins.
 */
class PinningDetector {

    companion object {
        private const val TAG = "PinningDetector"

        // Apps that use certificate pinning AND/OR end-to-end encryption at the
        // application layer. MITM on these will always fail — attempting it causes
        // connection errors visible to the user, so we skip them outright and show
        // an informative "pinned" badge in the Payload Inspector instead.
        val PINNED_PACKAGES: Set<String> = setOf(
            // Messaging — cert pinning + app-level E2E (Signal protocol)
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.signal.android",
            // Google — strict cert pinning across all services
            "com.google.android.gm",
            "com.google.android.apps.messaging",
            "com.google.android.youtube",
            // Meta
            "com.instagram.android",
            "com.facebook.katana",
            "com.facebook.orca",
            // Banking / payments — will refuse MITM and may trigger fraud alerts
            "com.paypal.android.p2pmobile",
            "com.google.android.apps.walletnfcrel",
        )

        // Domains that are always pinned regardless of the app.
        // Attempting MITM here causes SSL errors and user-visible breakage.
        val PINNED_DOMAINS: Set<String> = setOf(
            // Apple push — unrelated to Android but sometimes appears in split-tunnel
            "push.apple.com",
            "gateway.push.apple.com",
            // Google QUIC/h3 endpoints — these bypass TCP entirely
            "clients1.google.com",
            "clients2.google.com",
            // WhatsApp backend
            "e2e.whatsapp.com",
            "e2e-keys.whatsapp.com",
        )
    }

    // Runtime detected pinned domains (from SSLHandshakeExceptions)
    private val dynamicPinnedDomains = ConcurrentHashMap.newKeySet<String>()

    /**
     * Check if a domain or package uses certificate pinning
     *
     * @param packageName The Android package name (may be null)
     * @param domain The SNI domain
     * @return true if pinned, false otherwise
     */
    fun isPinned(packageName: String?, domain: String?): Boolean {
        if (domain == null) return false

        val result = when {
            PINNED_DOMAINS.any { domain.equals(it, ignoreCase = true) } -> true
            PINNED_DOMAINS.any { domain.endsWith(".$it", ignoreCase = true) } -> true
            packageName != null && PINNED_PACKAGES.contains(packageName) -> true
            dynamicPinnedDomains.contains(domain) -> true
            else -> false
        }

        if (result) {
            Log.d(TAG, "Domain marked as pinned: $domain (pkg=$packageName)")
        }

        return result
    }

    /**
     * Mark a domain as dynamically pinned after an SSL handshake failure
     * Called when SSLHandshakeException indicates certificate validation failure
     *
     * @param domain The domain that failed
     */
    fun markAsPinned(domain: String) {
        if (dynamicPinnedDomains.add(domain)) {
            Log.w(TAG, "Dynamically marked domain as pinned: $domain")
        }
    }

    /**
     * Get all known pinned domains (both static and dynamic)
     * @return Set of domain strings
     */
    fun allKnownPinnedDomains(): Set<String> {
        return (PINNED_DOMAINS + dynamicPinnedDomains).toSet()
    }

    /**
     * Check if a domain was dynamically detected as pinned
     */
    fun isDynamicPinned(domain: String): Boolean = dynamicPinnedDomains.contains(domain)

    /**
     * Clear dynamic pinned domains (e.g., on app restart)
     */
    fun clearDynamicPins() {
        dynamicPinnedDomains.clear()
        Log.d(TAG, "Cleared dynamic pinned domains")
    }
}