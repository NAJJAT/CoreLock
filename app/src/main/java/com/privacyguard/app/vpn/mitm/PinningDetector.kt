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

        // Hardcoded known pinned packages (based on public documentation)
        private val PINNED_PACKAGES = setOf(
            "com.whatsapp",
            "com.instagram.android",
            "com.facebook.katana",
            "com.twitter.android",
            "com.google.android.gm",
            "com.snapchat.android",
            "com.zhiliaoapp.musically",
            "net.one97.paytm",
            "com.phonepe.app",
            "com.bankofamerica.mobile",
            "com.chase.sig.android"
        )

        // Hardcoded known pinned domains
        private val PINNED_DOMAINS = setOf(
            "api.whatsapp.com",
            "graph.facebook.com",
            "api.twitter.com",
            "mail.google.com",
            "accounts.google.com",
            "appleid.apple.com",
            "api.snapchat.com",
            "push.apple.com",
            "gateway.push.apple.com"
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