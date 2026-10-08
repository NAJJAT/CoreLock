package com.privacyguard.core.packet

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class Ipv6AddressTest {

    private fun format(addr: String): String =
        Ipv6Packet.parseAddress(InetAddress.getByName(addr).address, 0)

    @Test
    fun aLoneZeroGroupKeepsItsSeparators() {
        // From a real WhatsApp connection that failed with "Unable to resolve host".
        assertEquals("2a03:2880:f221:c6:face:b00c:0:7260", format("2a03:2880:f221:c6:face:b00c:0:7260"))
    }

    @Test
    fun theLongestZeroRunIsCompressed() {
        assertEquals("2001:db8::1", format("2001:db8:0:0:0:0:0:1"))
        assertEquals("fd00:1:fd00:1::2", format("fd00:1:fd00:1:0:0:0:2"))
        assertEquals("::1", format("::1"))
        assertEquals("fe80::", format("fe80::"))
    }

    @Test
    fun formattedAddressesRoundTrip() {
        listOf("2a03:2880:f221:c6:face:b00c:0:7260", "2001:db8:0:1:0:0:0:1", "1:0:2:0:3:0:4:0").forEach { a ->
            val bytes = InetAddress.getByName(a).address
            val text = Ipv6Packet.parseAddress(bytes, 0)
            assertEquals(InetAddress.getByName(a), InetAddress.getByName(text))
        }
    }
}
