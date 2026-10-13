package com.privacyguard.vpn.dualvpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which UDP the VPN drops while dual-hop is on (SOCKS5 carries TCP only). */
class DualVpnConfigTest {

    private val chain = DualVpnConfig(enabled = true, firstHopHost = "hop1.example", secondHopHost = "hop2.example")

    @Test
    fun quic_is_always_dropped_while_the_chain_is_on() {
        assertTrue(chain.dropsUdp(443))
    }

    @Test
    fun other_udp_goes_direct_unless_the_user_blocks_it() {
        assertFalse(chain.dropsUdp(3478))
        assertTrue(chain.copy(blockUdpOutsideChain = true).dropsUdp(3478))
    }

    @Test
    fun nothing_is_dropped_when_the_chain_is_off_or_incomplete() {
        assertFalse(chain.copy(enabled = false).dropsUdp(443))
        assertFalse(chain.copy(secondHopHost = "").dropsUdp(443))
        assertFalse(chain.copy(enabled = false, blockUdpOutsideChain = true).dropsUdp(3478))
    }
}
