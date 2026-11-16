package com.privacyguard.vpn.mitm

import java.math.BigInteger
import java.security.SecureRandom
import java.util.Random

/**
 * Certificate serial numbers: positive and at most 20 bytes (RFC 5280 §4.1.2.2), with
 * 127 random bits. `nextLong().absoluteValue` was negative for Long.MIN_VALUE, and a
 * plain random BigInteger can be zero; both are invalid serials.
 */
object CertSerial {
    fun next(random: Random = SecureRandom()): BigInteger = BigInteger(127, random).add(BigInteger.ONE)
}
