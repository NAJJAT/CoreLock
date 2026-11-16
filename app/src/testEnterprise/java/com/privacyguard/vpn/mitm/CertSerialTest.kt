package com.privacyguard.vpn.mitm

import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.util.Random
import kotlin.math.absoluteValue

class CertSerialTest {

    private fun valid(s: BigInteger) = s.signum() > 0 && s.toByteArray().size <= 20

    /** A random source that only ever yields [b]: the extremes a real one can hit. */
    private class Fixed(private val b: Byte) : Random() {
        override fun nextBytes(bytes: ByteArray) { bytes.fill(b) }
        override fun next(bits: Int): Int = if (b == 0.toByte()) 0 else -1 ushr (32 - bits)
    }

    @Test fun the_old_formula_could_be_negative() {
        // Why the change: the smallest Long has no positive counterpart.
        assertTrue(Long.MIN_VALUE.absoluteValue < 0)
    }

    @Test fun serials_are_positive_and_fit_in_20_bytes_even_at_the_extremes() {
        assertTrue(valid(CertSerial.next(Fixed(0))))
        assertTrue(valid(CertSerial.next(Fixed(-1))))
        val rnd = Random(0x5E21)
        repeat(10_000) { assertTrue(valid(CertSerial.next(rnd))) }
    }
}
