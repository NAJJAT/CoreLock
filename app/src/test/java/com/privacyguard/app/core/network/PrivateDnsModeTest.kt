package com.privacyguard.app.core.network

import com.privacyguard.app.core.network.PrivateDnsCheck.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Diagnostics' Private DNS row. Android's own setting decides Off or Automatic:
 * `isPrivateDnsActive` is true only when DNS over TLS works on that network, so in
 * Automatic mode with a resolver that has no DoT (the owner's phone, 2026-11-10) the
 * row used to say "Off".
 */
class PrivateDnsModeTest {

    @Test
    fun automatic_is_automatic_even_when_dot_is_not_in_use() =
        assertEquals(Mode.AUTOMATIC, PrivateDnsCheck.mode(setting = "opportunistic", hostnameOnLink = false, activeOnLink = false))

    @Test
    fun off_is_off() =
        assertEquals(Mode.OFF, PrivateDnsCheck.mode(setting = "off", hostnameOnLink = false, activeOnLink = false))

    @Test
    fun a_hostname_on_the_network_is_hostname_mode_whatever_the_setting_reads() {
        assertEquals(Mode.HOSTNAME, PrivateDnsCheck.mode(setting = "hostname", hostnameOnLink = true, activeOnLink = true))
        assertEquals(Mode.HOSTNAME, PrivateDnsCheck.mode(setting = null, hostnameOnLink = true, activeOnLink = false))
    }

    @Test
    fun unreadable_setting_falls_back_to_what_the_network_shows() {
        // DoT in use without a hostname can only be Automatic.
        assertEquals(Mode.AUTOMATIC, PrivateDnsCheck.mode(setting = null, hostnameOnLink = false, activeOnLink = true))
        // Off and Automatic look the same from here: say so rather than guess "Off".
        assertEquals(Mode.OFF_OR_AUTOMATIC, PrivateDnsCheck.mode(setting = null, hostnameOnLink = false, activeOnLink = false))
        assertEquals(Mode.OFF_OR_AUTOMATIC, PrivateDnsCheck.mode(setting = "something-new", hostnameOnLink = false, activeOnLink = false))
    }

    @Test
    fun hostname_setting_not_yet_on_the_network_is_still_hostname_mode() =
        // Android applies the hostname to the network a moment later; it is already the setting.
        assertEquals(Mode.HOSTNAME, PrivateDnsCheck.mode(setting = "hostname", hostnameOnLink = false, activeOnLink = false))

    @Test
    fun the_setting_is_read_case_and_space_insensitively() =
        assertEquals(Mode.AUTOMATIC, PrivateDnsCheck.mode(setting = " Opportunistic ", hostnameOnLink = false, activeOnLink = false))
}
