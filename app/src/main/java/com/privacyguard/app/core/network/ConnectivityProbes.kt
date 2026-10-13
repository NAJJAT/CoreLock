package com.privacyguard.app.core.network

import com.privacyguard.app.data.db.ConnectionEntity

/**
 * The operating system's own connectivity checks: Android's captive-portal probe and
 * Samsung's MobileWips (Wi-Fi intrusion protection). They use plain HTTP on purpose,
 * because a captive portal can only intercept unencrypted requests, and they carry no
 * personal data. They are shown as "Connectivity check" and never counted as
 * unencrypted traffic.
 */
object ConnectivityProbes {

    /** System components that only make these checks. */
    private val probePackages = setOf(
        "com.google.android.networkstack",
        "com.android.networkstack",
        "com.samsung.android.networkstack",
        "com.samsung.android.wcmurlsnetworkstack",
        "com.samsung.android.server.wifi.mobilewips",
        "com.google.android.captiveportallogin",
        "com.android.captiveportallogin",
    )

    /**
     * Hosts that exist only to answer connectivity checks. A general host such as
     * www.google.com is never listed, even though Android also probes it.
     */
    private val probeHosts = listOf(
        "connectivitycheck.gstatic.com", "connectivitycheck.android.com",
        "clients1.google.com", "clients3.google.com", "clients4.google.com",
        "captive.apple.com", "www.msftconnecttest.com", "www.msftncsi.com",
        "detectportal.firefox.com", "connectivity-check.ubuntu.com", "nmcheck.gnome.org",
    )

    fun isProbe(packageName: String?, host: String?): Boolean {
        if (packageName != null && packageName in probePackages) return true
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return probeHosts.any { h == it || h.endsWith(".$it") }
    }

    fun isProbe(c: ConnectionEntity): Boolean =
        isProbe(c.packageName, c.sniHostname) || isProbe(null, c.domain)
}
