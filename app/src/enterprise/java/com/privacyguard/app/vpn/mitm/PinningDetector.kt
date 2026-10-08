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
class PinningDetector(private val store: Store = Store.InMemory) {

    /** Where learned pins survive VPN restarts; the in-memory default is for tests. */
    interface Store {
        fun load(): Pair<Set<String>, Set<String>>   // (domains, packages)
        fun save(domains: Set<String>, packages: Set<String>)

        object InMemory : Store {
            override fun load() = emptySet<String>() to emptySet<String>()
            override fun save(domains: Set<String>, packages: Set<String>) {}
        }
    }

    /** How the device side of a MITM handshake ended. */
    enum class Rejection {
        /** The app sent a TLS alert (unknown CA, bad certificate, ...): it does not trust us. */
        ALERT,
        /** The app closed the socket without an alert: a rejection, or just a cancelled preconnect. */
        CLOSED,
        /** The real server's TLS failed, not the app's. */
        UPSTREAM,
    }

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

        /** Messengers that encrypt end-to-end, so even a decrypted TLS layer shows no content. */
        val END_TO_END_PACKAGES: Set<String> = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.signal.android", "org.thoughtcrime.securesms",
            "com.facebook.orca", "im.vector.app", "ch.threema.app", "com.wire",
        )

        /** Browsers that trust user-installed CAs, so a rejection is about one site. */
        val BROWSER_PACKAGES: Set<String> = setOf(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
            "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.focus",
            "com.microsoft.emmx", "com.brave.browser", "com.opera.browser",
            "com.sec.android.app.sbrowser", "com.duckduckgo.mobile.android",
            "com.vivaldi.browser", "com.kiwibrowser.browser",
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

        // Domains we must NOT intercept because doing so breaks the whole device,
        // not just one app. Matched by exact host or as a parent suffix.
        //
        //  - DNS-over-HTTPS resolvers: the browser/OS rejects a user CA here and,
        //    because ALL name resolution rides these, intercepting them means the
        //    device can no longer resolve ANY domain. Bypassing them is what lets
        //    real app requests (the ones worth inspecting) resolve and appear.
        //  - Google/Samsung sign-in and update infrastructure: hard-pinned, so
        //    interception only ever fails and can lock the user out of services.
        val BYPASS_DOMAINS: Set<String> = setOf(
            // DNS-over-HTTPS resolvers
            "dns.google",
            "cloudflare-dns.com",           // chrome/mozilla.cloudflare-dns.com
            "one.one.one.one",
            "dns.quad9.net",
            "dns9.quad9.net",
            "dns11.quad9.net",
            "doh.opendns.com",
            "dns.adguard.com",
            "dns.adguard-dns.com",
            "doh.cleanbrowsing.org",
            "dns.nextdns.io",
            // Google hard-pinned infrastructure (sign-in, updates, safety)
            "accounts.google.com",
            "googleapis.com",               // *-pa.googleapis.com, play etc.
            "gstatic.com",
            "clients3.google.com",
            "clients4.google.com",
            "clients5.google.com",
            "clients6.google.com",
            "update.googleapis.com",
            "safebrowsing.googleapis.com",
            // Samsung infrastructure
            "samsungcloud.com",
            "samsungdm.com",
            "samsungapps.com",
            "ospserver.net",
        )
    }

    // Runtime detected pinned domains (from SSLHandshakeExceptions)
    private val dynamicPinnedDomains = ConcurrentHashMap.newKeySet<String>()

    /**
     * Apps seen rejecting the PrivacyGuard CA. Since Android 7 an app trusts user
     * CAs only if it opts in, so one rejection means it rejects every host: without
     * this, each new server it contacts failed once (a dropped connection and a
     * retry in the app) before being passed through.
     */
    private val untrustingPackages = ConcurrentHashMap.newKeySet<String>()

    /**
     * Hosts whose interception failed on our side (the real server's TLS, or the
     * local redirect). Passed through until the VPN restarts and never persisted:
     * the cause may be temporary, and it says nothing about the app's trust.
     */
    private val skippedForNow = ConcurrentHashMap.newKeySet<String>()

    /** Handshakes the app closed without an alert, per domain. */
    private val silentCloses = ConcurrentHashMap<String, Int>()

    init {
        val (domains, packages) = store.load()
        dynamicPinnedDomains.addAll(domains)
        untrustingPackages.addAll(packages)
    }

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
            isBypassDomain(domain) -> true
            PINNED_DOMAINS.any { domain.equals(it, ignoreCase = true) } -> true
            PINNED_DOMAINS.any { domain.endsWith(".$it", ignoreCase = true) } -> true
            packageName != null && PINNED_PACKAGES.contains(packageName) -> true
            packageName != null && untrustingPackages.contains(packageName) -> true
            dynamicPinnedDomains.contains(domain) -> true
            else -> false
        }

        return result
    }

    /** Whether to leave this connection alone: pinned, or skipped after our own failure. */
    fun shouldPassThrough(packageName: String?, domain: String?): Boolean =
        isPinned(packageName, domain) || (domain != null && domain in skippedForNow)

    /** Interception of [domain] failed on our side; see [skippedForNow]. */
    fun skipForNow(domain: String) {
        if (skippedForNow.add(domain)) Log.w(TAG, "Passing $domain through until restart (interception failed on our side)")
    }

    fun isSkippedForNow(domain: String): Boolean = domain in skippedForNow

    /**
     * True if [domain] is on the never-intercept [BYPASS_DOMAINS] list (exact host
     * or a subdomain of a listed parent). Intercepting these breaks connectivity.
     */
    fun isBypassDomain(domain: String): Boolean =
        BYPASS_DOMAINS.any { domain.equals(it, ignoreCase = true) || domain.endsWith(".$it", ignoreCase = true) }

    /**
     * Mark a domain as dynamically pinned after an SSL handshake failure
     * Called when SSLHandshakeException indicates certificate validation failure
     *
     * @param domain The domain that failed
     */
    fun markAsPinned(domain: String) {
        if (dynamicPinnedDomains.add(domain)) {
            Log.w(TAG, "Dynamically marked domain as pinned: $domain")
            persist()
        }
    }

    /**
     * Records a failed MITM handshake and decides what to pass through from now on.
     * Only the app's side of the handshake counts as a rejection (an alert, or a
     * close before it completed); the real server failing is [skipForNow]. Errors
     * after the handshake never reach here. A browser trusts user CAs, so its rejection pins only the [domain]; any other
     * app's rejection pins the whole app. A silent close is only believed the second
     * time for the same domain: browsers often cancel speculative connections
     * mid-handshake, which says nothing about trust.
     */
    fun recordRejection(packageName: String?, domain: String, rejection: Rejection) {
        when (rejection) {
            Rejection.UPSTREAM -> skipForNow(domain)
            Rejection.ALERT -> rejectFor(packageName, domain)
            Rejection.CLOSED -> {
                val closes = silentCloses.merge(domain, 1, Int::plus) ?: 1
                if (closes >= 2) rejectFor(packageName, domain)
            }
        }
    }

    /** A successful interception: forget earlier inconclusive closes. */
    fun recordSuccess(domain: String) {
        silentCloses.remove(domain)
        skippedForNow.remove(domain)
    }

    private fun rejectFor(packageName: String?, domain: String) {
        silentCloses.remove(domain)
        if (packageName == null || packageName in BROWSER_PACKAGES) {
            markAsPinned(domain)
        } else if (untrustingPackages.add(packageName)) {
            Log.w(TAG, "$packageName does not trust the PrivacyGuard CA - passing all its traffic through")
            persist()
        }
    }

    /** Apps seen rejecting the CA, for the UI. */
    fun untrustingPackages(): Set<String> = untrustingPackages.toSet()

    private fun persist() = store.save(dynamicPinnedDomains.toSet(), untrustingPackages.toSet())

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
        untrustingPackages.clear()
        silentCloses.clear()
        skippedForNow.clear()
        persist()
        Log.d(TAG, "Cleared dynamic pinned domains")
    }
}