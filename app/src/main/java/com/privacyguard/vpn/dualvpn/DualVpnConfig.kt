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
) {
    val isValid: Boolean get() =
        enabled && firstHopHost.isNotBlank() && secondHopHost.isNotBlank()
}
