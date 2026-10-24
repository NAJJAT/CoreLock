package com.privacyguard.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockRowGateTest {

    private val t0 = 1_800_000_000_000L

    private fun BlockRowGate.write(
        at: Long, uid: Int = 10_100, host: String = "dns.google", port: Int = 443,
        protocol: String = "TCP", source: BlockSource? = BlockSource.DOH,
    ) = shouldWrite(uid, host, port, protocol, source, at)

    @Test
    fun a_retry_storm_writes_one_row_per_minute_and_counts_the_rest() {
        val gate = BlockRowGate()
        // Chrome's DoH retries, measured on the emulator: ~6 per second for 5 minutes.
        val attempts = (0 until 1_800).map { t0 + it * 166L }
        val written = attempts.count { gate.write(it) }
        assertEquals(5, written)
        assertEquals(1_795, attempts.size - written)
    }

    @Test
    fun the_window_is_fixed_not_sliding() {
        val gate = BlockRowGate()
        // Every 10 s for 3 minutes: a sliding window would keep the first row forever.
        val written = (0 until 18).count { gate.write(t0 + it * 10_000L) }
        assertEquals(3, written)
    }

    @Test
    fun a_new_window_starts_exactly_after_sixty_seconds() {
        val gate = BlockRowGate()
        assertTrue(gate.write(t0))
        assertFalse(gate.write(t0 + 59_999))
        assertTrue(gate.write(t0 + 60_000))
    }

    @Test
    fun app_host_port_protocol_and_source_are_kept_apart() {
        val gate = BlockRowGate()
        assertTrue(gate.write(t0))
        assertTrue(gate.write(t0, uid = 10_200))
        assertTrue(gate.write(t0, host = "dns.quad9.net"))
        assertTrue(gate.write(t0, port = 853))
        assertTrue(gate.write(t0, protocol = "UDP"))
        assertTrue(gate.write(t0, source = BlockSource.SNI))
        assertTrue(gate.write(t0, source = null))
        assertFalse(gate.write(t0 + 1))
    }

    @Test
    fun host_case_and_trailing_dot_are_one_host() {
        val gate = BlockRowGate()
        assertTrue(gate.write(t0, host = "Ads.Example.com."))
        assertFalse(gate.write(t0 + 1, host = "ads.example.com"))
    }

    @Test
    fun past_the_key_limit_the_oldest_is_forgotten_never_a_new_destination() {
        val gate = BlockRowGate(maxKeys = 3)
        assertTrue(gate.write(t0, host = "a.example"))
        assertTrue(gate.write(t0, host = "b.example"))
        assertTrue(gate.write(t0, host = "c.example"))
        assertTrue(gate.write(t0, host = "d.example"))          // a.example is forgotten
        assertTrue(gate.write(t0 + 1, host = "a.example"))      // an extra row, not a lost one
        assertFalse(gate.write(t0 + 1, host = "d.example"))
    }

    @Test
    fun a_clock_set_back_writes_a_row_instead_of_merging_forever() {
        val gate = BlockRowGate()
        assertTrue(gate.write(t0))
        assertTrue(gate.write(t0 - 3_600_000))
    }
}
