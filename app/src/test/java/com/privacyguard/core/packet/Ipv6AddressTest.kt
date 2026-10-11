package com.privacyguard.core.packet

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class Ipv6AddressTest {

    private fun format(addr: String): String {
        val bytes = InetAddress.getByName(addr).address
        return Ipv6Packet.parseAddress(bytes, 0)
    }

    @Test
    fun `single zero group keeps its separators`() {
        // Seen on device: formatted as "...:b00c:02", which is not an address.
        assertEquals("2a03:2880:f37a:8d:face:b00c:0:2", format("2a03:2880:f37a:8d:face:b00c:0:2"))
    }

    @Test
    fun `longest zero run is compressed`() {
        assertEquals("2a02:1810:ac38:4300::1", format("2a02:1810:ac38:4300:0:0:0:1"))
        assertEquals("::1", format("::1"))
        assertEquals("fd00:1:fd00:1::2", format("fd00:1:fd00:1:0:0:0:2"))
        assertEquals("1::2:0:3", format("1:0:0:0:0:2:0:3"))
    }

    @Test
    fun `every formatted address parses back to the same bytes`() {
        val samples = listOf("2a03:2880:f37a:8d:face:b00c:0:2", "0:1:0:1:0:1:0:1", "2001:db8::", "1:0:2:0:3:0:4:0")
        for (s in samples) {
            val bytes = InetAddress.getByName(s).address
            val text = Ipv6Packet.parseAddress(bytes, 0)
            val back = ByteArray(16).also { Ipv6Packet.writeAddress(it, 0, text) }
            assertEquals(s, bytes.toList(), back.toList())
        }
    }
}
