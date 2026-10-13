package com.privacyguard.app.core.detection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownPushHostsTest {
    @Test
    fun push_channels_match() {
        listOf("mtalk.google.com", "alt3-mtalk.google.com", "1-courier.push.apple.com", "g.whatsapp.net",
            "edge-mqtt.facebook.com", "mqtt.example.org", "connectivitycheck.gstatic.com").forEach {
            assertTrue(it, KnownPushHosts.isPushHost(it))
        }
    }

    @Test
    fun ordinary_hosts_do_not() {
        listOf("api.example.com", "graph.facebook.com", "notwhatsapp.net", "mqttx.example.com", null).forEach {
            assertFalse("$it", KnownPushHosts.isPushHost(it))
        }
    }
}
