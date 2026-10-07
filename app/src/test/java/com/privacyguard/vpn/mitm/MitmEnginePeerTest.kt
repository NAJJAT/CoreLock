package com.privacyguard.vpn.mitm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class MitmEnginePeerTest {

    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test fun acceptsOwnLoopbackSocket() {
        assertTrue(MitmEngine.isExpectedPeer(loopback, 40123, 40123))
    }

    @Test fun rejectsOtherLocalPort() {
        assertFalse(MitmEngine.isExpectedPeer(loopback, 40124, 40123))
    }

    @Test fun rejectsNonLoopbackPeer() {
        assertFalse(MitmEngine.isExpectedPeer(InetAddress.getByName("192.168.1.20"), 40123, 40123))
    }

    @Test fun rejectsWhenNoPortWasBound() {
        assertFalse(MitmEngine.isExpectedPeer(loopback, -1, -1))
        assertFalse(MitmEngine.isExpectedPeer(null, 40123, 40123))
    }
}
