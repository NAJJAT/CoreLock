package com.privacyguard.vpn.dualvpn

data class DualVpnConfig(
    val enabled: Boolean = false,
    val firstHopHost: String = "",
    val firstHopPort: Int = 1080,
    val firstHopUser: String = "",
    val firstHopPassword: String = "",
    val secondHopHost: String = "",
    val secondHopPort: Int = 1080,
    val secondHopUser: String = "",
    val secondHopPassword: String = "",
    /** Drop all UDP while dual-hop is on (calls, games); off by default. */
    val blockUdpOutsideChain: Boolean = false,
) {
    // Ports are range-checked: an out-of-range value (e.g. a mistyped "10811080")
    // was cut to 16 bits and silently dialled some other port (63176).
    val isValid: Boolean get() =
        enabled && firstHopHost.isNotBlank() && secondHopHost.isNotBlank() &&
            firstHopPort in 1..65535 && secondHopPort in 1..65535

    /**
     * What goes through the chain: every TCP connection, and DNS when DoH is on (DoH
     * runs over TCP through it). SOCKS5 CONNECT carries only TCP, so UDP cannot use
     * the chain: QUIC (UDP 443) is always dropped while dual-hop is on, so apps retry
     * over TCP through the chain; other UDP goes direct unless [blockUdpOutsideChain].
     * Plain DNS (DoH off) is answered by PrivacyGuard's own resolver path and goes direct.
     */
    fun dropsUdp(destinationPort: Int): Boolean =
        isValid && (destinationPort == 443 || blockUdpOutsideChain)
}
