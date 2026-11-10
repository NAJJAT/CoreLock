package com.privacyguard.app.vpn

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The tunnel must not turn Wi-Fi into a metered network. From Android 10 a VPN counts as
 * metered unless the app calls VpnService.Builder.setMetered(false); without it, "Wi-Fi only"
 * work in every app (and our own weekly UNMETERED list refresh) waited for as long as
 * protection was on (Galaxy Note20, Android 13, 2026-11-10). The Builder can't run on the
 * JVM, so this reads openTun() itself, like BrandNameTest does for setSession.
 */
class VpnMeteredTest {

    private val openTun: String = File("src/main/java/com/privacyguard/platform/android/PrivacyVpnService.kt")
        .readText()
        .substringAfter("private fun openTun()")
        // Up to the builder's last call; the log line above it also says "establish()".
        .substringBefore(".also {")
        .lines()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    @Test
    fun the_tunnel_takes_meteredness_from_the_underlying_network_on_android_10_and_newer() {
        assertTrue(
            "openTun() must call setMetered(false) before establish()",
            Regex("""Build\.VERSION\.SDK_INT\s*>=\s*Build\.VERSION_CODES\.Q\s*\)\s*setMetered\(false\)""").containsMatchIn(openTun),
        )
    }

    @Test
    fun no_underlying_networks_are_pinned_so_the_default_network_decides() {
        // setUnderlyingNetworks(emptyArray()) would make the VPN look offline-unmetered, and a
        // fixed network would go stale on Wi-Fi <-> mobile switches; null (not calling it)
        // makes Android follow the default network.
        assertTrue("openTun() must not call setUnderlyingNetworks", "setUnderlyingNetworks" !in openTun)
    }
}
