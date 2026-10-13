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
    // Ports are range-checked: an out-of-range value (e.g. a mistyped "10811080")
    // was cut to 16 bits and silently dialled some other port (63176).
    val isValid: Boolean get() =
        enabled && firstHopHost.isNotBlank() && secondHopHost.isNotBlank() &&
            firstHopPort in 1..65535 && secondHopPort in 1..65535
}
