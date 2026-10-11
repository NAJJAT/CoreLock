package com.privacyguard.app.adprivacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdSdkPolicyTest {

    private var enabled = true
    private val policy = AdSdkPolicy(enabled = { enabled })

    @Test
    fun audience_network_is_blocked_in_other_apps() {
        val v = policy.decide("com.example.game", "an.facebook.com")
        assertTrue(v is AdSdkVerdict.Block)
        assertEquals("meta_audience_network", (v as AdSdkVerdict.Block).sdk.id)
    }

    @Test
    fun meta_sdk_hosts_are_left_alone_inside_meta_apps() {
        assertEquals(AdSdkVerdict.NoOpinion, policy.decide("com.facebook.katana", "an.facebook.com"))
        assertEquals(AdSdkVerdict.NoOpinion, policy.decide("com.instagram.android", "connect.facebook.net"))
    }

    @Test
    fun facebook_login_host_is_never_blocked_even_in_other_apps() {
        // Login and app events share graph.facebook.com; blocking it would break "Continue with Facebook".
        assertTrue(policy.decide("com.example.game", "graph.facebook.com") is AdSdkVerdict.KeepOpen)
        assertTrue(policy.decide("com.spotify.music", "m.facebook.com") is AdSdkVerdict.KeepOpen)
    }

    @Test
    fun push_and_deep_link_hosts_stay_open() {
        assertEquals(NeverBlockReason.PUSH,
            (policy.decide("com.example", "firebaseinstallations.googleapis.com") as AdSdkVerdict.KeepOpen).rule.reason)
        assertEquals(NeverBlockReason.DEEP_LINKS,
            (policy.decide("com.example", "myapp.app.link") as AdSdkVerdict.KeepOpen).rule.reason)
    }

    @Test
    fun attribution_sdk_subdomains_are_blocked() {
        assertTrue(policy.decide("com.example", "inapps.appsflyersdk.com") is AdSdkVerdict.Block)
        assertTrue(policy.decide("com.example", "app.adjust.com") is AdSdkVerdict.Block)
        assertTrue(policy.decide("com.example", "APP-MEASUREMENT.COM.") is AdSdkVerdict.Block)
    }

    @Test
    fun lookalike_hosts_do_not_match() {
        assertEquals(AdSdkVerdict.NoOpinion, policy.decide("com.example", "notadjust.com"))
        assertEquals(AdSdkVerdict.NoOpinion, policy.decide("com.example", "facebook.com.evil.example"))
    }

    @Test
    fun turning_blocking_off_keeps_the_exceptions() {
        enabled = false
        assertEquals(AdSdkVerdict.NoOpinion, policy.decide("com.example", "app.adjust.com"))
        assertTrue(policy.decide("com.example", "graph.facebook.com") is AdSdkVerdict.KeepOpen)
    }
}
