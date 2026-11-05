package com.privacyguard.vpn.firewall

/**
 * 64-bit fingerprints of domain names, and sorted arrays of them (ADR 0014).
 *
 * A fingerprint is FNV-1a over the name's characters, lower-cased ASCII, finished with
 * MurmurHash3's fmix64 so nearby names spread over the whole range. It is computed over a
 * range of a string, so a lookup can fingerprint each parent of a name without creating a
 * substring: no allocation on the packet path.
 */
internal object NameFingerprints {

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL   // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L

    /** Fingerprint of [s] from [start] (inclusive) to [end] (exclusive), letters lower-cased (char by char, as the stored names are). */
    fun of(s: CharSequence, start: Int = 0, end: Int = s.length): Long {
        var h = FNV_OFFSET
        for (i in start until end) {
            var c = s[i].code
            if (c in 'A'.code..'Z'.code) c += 32
            else if (c >= 0x80) c = Character.toLowerCase(c)   // as String.lowercase() for a stored name
            // Two bytes per char: a non-ASCII name can't alias an ASCII one by its low byte.
            h = (h xor (c and 0xFF).toLong()) * FNV_PRIME
            h = (h xor (c ushr 8).toLong()) * FNV_PRIME
        }
        return fmix64(h)
    }

    private fun fmix64(k0: Long): Long {
        var k = k0
        k = k xor (k ushr 33)
        k *= -0xae502812aa7333L        // 0xff51afd7ed558ccd
        k = k xor (k ushr 33)
        k *= -0x3b314601e57a13adL      // 0xc4ceb9fe1a85ec53
        return k xor (k ushr 33)
    }

    /** [values] sorted and without duplicates; may return [values] itself, reordered. */
    fun sortedUnique(values: LongArray, count: Int = values.size): LongArray {
        if (count == 0) return EMPTY
        java.util.Arrays.sort(values, 0, count)
        var w = 1
        for (r in 1 until count) if (values[r] != values[w - 1]) values[w++] = values[r]
        return if (w == values.size) values else values.copyOf(w)
    }

    fun contains(sorted: LongArray, value: Long): Boolean = java.util.Arrays.binarySearch(sorted, value) >= 0

    val EMPTY = LongArray(0)
}

/**
 * A sorted, unique fingerprint array with a directory on the top bits, so a lookup is a
 * binary search over a few neighbouring values instead of the whole array: one or two
 * cache misses rather than about 19 at 500k names. Fingerprints are evenly spread (fmix64),
 * so each directory slot covers about four values. The directory costs about 1 byte per name.
 */
internal class FingerprintSet(val sorted: LongArray) {

    private val bits: Int
    private val index: IntArray

    init {
        // About 4 values per slot; small arrays get no directory.
        var b = 0
        while (b < 22 && (1 shl (b + 2)) <= sorted.size) b++
        bits = b
        if (b == 0) {
            index = EMPTY_INDEX
        } else {
            val slots = 1 shl b
            index = IntArray(slots + 1)
            var pos = 0
            for (s in 0 until slots) {
                index[s] = pos
                while (pos < sorted.size && slot(sorted[pos]) == s) pos++
            }
            index[slots] = sorted.size
        }
    }

    /** Top [bits] bits, sign bit flipped, so slot order is the signed order of the array. */
    private fun slot(v: Long): Int = (v ushr (64 - bits)).toInt() xor (1 shl (bits - 1))

    val size: Int get() = sorted.size

    /** Bytes held: the array and the directory (for the memory report, ADR 0014). */
    val bytes: Long get() = 8L * sorted.size + 4L * index.size

    fun contains(v: Long): Boolean {
        if (bits == 0) return java.util.Arrays.binarySearch(sorted, v) >= 0
        val s = slot(v)
        val lo = index[s]
        val hi = index[s + 1]
        return lo < hi && java.util.Arrays.binarySearch(sorted, lo, hi, v) >= 0
    }

    companion object {
        private val EMPTY_INDEX = IntArray(0)
        val EMPTY = FingerprintSet(NameFingerprints.EMPTY)
    }
}
