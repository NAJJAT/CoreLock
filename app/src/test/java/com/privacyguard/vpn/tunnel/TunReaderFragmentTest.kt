package com.privacyguard.vpn.tunnel

import com.privacyguard.app.core.stats.StatsManager
import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.packet.UdpPacket
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.FileDescriptor

/**
 * IPv4 fragments never reach the packet handlers: a later fragment has no UDP or TCP
 * header, so its first bytes (chosen by the app) would be read as ports and the
 * connection could not be matched to its app or its rules. Each one is counted, and
 * the count reaches Diagnostics.
 */
class TunReaderFragmentTest {

    private val handled = mutableListOf<IpPacket>()
    private val reader = TunReader(TunInterface(FileDescriptor())).apply { addHandler { handled += it } }

    @Before fun resetStats() = StatsManager.reset()

    private fun udp(): ByteArray =
        IpPacket.build(IpPacket.PROTO_UDP, "10.0.0.2", "203.0.113.9",
            UdpPacket.build(40000, 5000, ByteArray(32) { 7 }).toBytes())

    /** Sets the More Fragments flag and the fragment offset (in 8-byte units). */
    private fun ByteArray.fragment(moreFragments: Boolean, offsetUnits: Int) = also {
        val v = (if (moreFragments) 0x2000 else 0) or (offsetUnits and 0x1FFF)
        it[6] = (v ushr 8).toByte(); it[7] = v.toByte()
    }

    @Test
    fun a_whole_packet_reaches_the_handlers() {
        reader.dispatch(udp())
        assertEquals(1, handled.size)
        assertEquals(0L, reader.droppedFragments.get())
    }

    @Test
    fun a_later_fragment_is_dropped_and_counted() {
        reader.dispatch(udp().fragment(moreFragments = false, offsetUnits = 185))
        assertEquals(0, handled.size)
        assertEquals(1L, reader.droppedFragments.get())
        StatsManager.publishNow()
        assertEquals(1L, StatsManager.snapshot.value.fragmentsDropped)
    }

    @Test
    fun a_first_fragment_is_dropped_and_counted() {
        reader.dispatch(udp().fragment(moreFragments = true, offsetUnits = 0))
        assertEquals(0, handled.size)
        assertEquals(1L, reader.droppedFragments.get())
    }

    @Test
    fun a_middle_fragment_is_dropped_and_counted() {
        reader.dispatch(udp().fragment(moreFragments = true, offsetUnits = 185))
        assertEquals(0, handled.size)
        assertEquals(1L, reader.droppedFragments.get())
    }
}
