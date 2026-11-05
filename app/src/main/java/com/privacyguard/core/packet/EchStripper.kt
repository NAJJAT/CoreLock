package com.privacyguard.core.packet

/**
 * Removes the ECH key (SvcParamKey 5, "ech") from HTTPS (65) and SVCB (64) records in a
 * DNS answer, so a browser makes a normal ClientHello whose server name CoreLock can read
 * (plan task 1.4, ADR 0015). Used only when the user turned the setting on, at Strict.
 *
 * Every other parameter stays (alpn, port, the address hints). Each changed record's
 * RDATA length, the section counts and every compression pointer behind the removed bytes
 * are rewritten. A record that can't be parsed safely (parameters out of order or
 * duplicated, a length past the record, a compressed or unterminated target, `mandatory`
 * naming ech) is removed rather than passed on with its key. An answer that can't be
 * parsed safely is [Result.Unreadable]: the caller answers with no records instead.
 *
 * Only answers to HTTPS and SVCB queries are rewritten: those are the records browsers
 * read the key from. For every other answer this is one walk over the question.
 *
 * Compression pointers are found in owner names and in the RDATA of the types RFC 1035
 * lets senders compress (NS, CNAME, SOA, PTR, MX) plus SRV and DNAME; RFC 3597 §4 forbids
 * compression in any other type, so its RDATA is copied as it is.
 *
 * NOTE (ADR 0015): the default of this setting must be reviewed when encrypted upstream
 * DNS ships as a default. Then ECH is the only thing still hiding the name from the network.
 */
object EchStripper {

    sealed interface Result {
        /** Nothing to change: pass the answer on as it is. */
        data object Unchanged : Result
        /** Can't be rewritten safely: answer the query with no records. */
        data object Unreadable : Result
        class Stripped(val message: ByteArray, val keysRemoved: Int, val recordsRemoved: Int) : Result
    }

    private const val HEADER = 12
    private const val TYPE_NS = 2
    private const val TYPE_CNAME = 5
    private const val TYPE_SOA = 6
    private const val TYPE_PTR = 12
    private const val TYPE_MX = 15
    private const val TYPE_SRV = 33
    private const val TYPE_DNAME = 39
    private const val TYPE_SVCB = 64
    private const val TYPE_HTTPS = 65
    private const val KEY_MANDATORY = 0
    private const val KEY_ECH = 5
    private const val MAX_NAME = 255
    /** Pointer hops per name: real names need a few; this bounds hostile chains. */
    private const val MAX_JUMPS = 32

    fun strip(m: ByteArray): Result {
        if (m.size < HEADER) return Result.Unreadable
        val qd = u16(m, 4)
        // Cheap check first, nothing recorded: is this an answer to an HTTPS/SVCB query?
        var p = HEADER
        var service = false
        repeat(qd) {
            p = walkName(m, p, null)
            // DnsHandler already refused answers it can't parse; this is the rest (a forward
            // pointer, a reserved label type). Its type is unknown, so it isn't passed on.
            if (p < 0 || p + 4 > m.size) return Result.Unreadable
            val type = u16(m, p)
            if (type == TYPE_HTTPS || type == TYPE_SVCB) service = true
            p += 4
        }
        if (!service) return Result.Unchanged
        return rewrite(m, qd)
    }

    private fun rewrite(m: ByteArray, qd: Int): Result {
        val pointers = IntList()          // positions of in-stream compression pointers
        val removed = IntList()           // [start, end) pairs, in message order
        val lenFields = IntList()         // (rdLength position, rdStart, rdEnd) of changed records
        val droppedIn = IntArray(3)
        var keys = 0
        var records = 0

        var p = HEADER
        repeat(qd) { p = walkName(m, p, pointers); if (p < 0) return Result.Unreadable; p += 4 }
        val counts = intArrayOf(u16(m, 6), u16(m, 8), u16(m, 10))
        for (section in 0..2) repeat(counts[section]) {
            val start = p
            val pointersBefore = pointers.size
            p = walkName(m, p, pointers)
            if (p < 0 || p + 10 > m.size) return Result.Unreadable
            val type = u16(m, p)
            val rdLen = u16(m, p + 8)
            val rdStart = p + 10
            val rdEnd = rdStart + rdLen
            if (rdEnd > m.size) return Result.Unreadable
            if (!walkRdataNames(m, type, rdStart, rdEnd, pointers)) return Result.Unreadable
            if (type == TYPE_HTTPS || type == TYPE_SVCB) {
                when (val ech = findEch(m, rdStart, rdEnd)) {
                    NO_ECH -> {}
                    BAD -> {
                        removed.add(start); removed.add(rdEnd)
                        pointers.truncate(pointersBefore)       // they go with the record
                        droppedIn[section]++; records++
                    }
                    else -> {
                        removed.add(ech); removed.add(ech + 4 + u16(m, ech + 2))
                        lenFields.add(p + 8); lenFields.add(rdStart); lenFields.add(rdEnd)
                        keys++
                    }
                }
            }
            p = rdEnd
        }
        if (removed.size == 0) return Result.Unchanged

        // Copy everything but the removed ranges.
        var total = 0
        for (i in 0 until removed.size step 2) total += removed[i + 1] - removed[i]
        val out = ByteArray(m.size - total)
        var from = 0
        var to = 0
        for (i in 0 until removed.size step 2) {
            val n = removed[i] - from
            System.arraycopy(m, from, out, to, n)
            to += n
            from = removed[i + 1]
        }
        System.arraycopy(m, from, out, to, m.size - from)

        // Section counts, record lengths, then every pointer behind a removed range.
        for (s in 0..2) put16(out, 6 + 2 * s, counts[s] - droppedIn[s])
        for (i in 0 until lenFields.size step 3) {
            val at = lenFields[i]
            val newLen = (lenFields[i + 2] - lenFields[i + 1]) - removedWithin(removed, lenFields[i + 1], lenFields[i + 2])
            put16(out, at - shift(removed, at), newLen)
        }
        for (i in 0 until pointers.size) {
            val at = pointers[i]
            val target = ((m[at].toInt() and 0x3F) shl 8) or (m[at + 1].toInt() and 0xFF)
            if (insideRemoved(removed, target)) return Result.Unreadable
            val moved = target - shift(removed, target)
            val newAt = at - shift(removed, at)
            out[newAt] = (0xC0 or (moved ushr 8)).toByte()
            out[newAt + 1] = moved.toByte()
        }
        return Result.Stripped(out, keys, records)
    }

    private const val NO_ECH = -1
    private const val BAD = -2

    /**
     * Position of the ech parameter in an HTTPS/SVCB RDATA, [NO_ECH], or [BAD] when the
     * record can't be trusted: then it is removed whole.
     */
    private fun findEch(m: ByteArray, rdStart: Int, rdEnd: Int): Int {
        if (rdEnd - rdStart < 3) return BAD
        // TargetName: uncompressed (RFC 9460 §2.2), labels inside the record.
        var p = rdStart + 2
        var nameLen = 0
        while (true) {
            if (p >= rdEnd) return BAD
            val len = m[p].toInt() and 0xFF
            if (len == 0) { p++; break }
            if (len > 63) return BAD
            nameLen += len + 1
            if (nameLen > MAX_NAME) return BAD
            p += 1 + len
        }
        var ech = NO_ECH
        var lastKey = -1
        var mandatoryNamesEch = false
        while (p < rdEnd) {
            if (p + 4 > rdEnd) return BAD
            val key = u16(m, p)
            val len = u16(m, p + 2)
            val valueEnd = p + 4 + len
            // RFC 9460 §2.2: keys strictly increasing, so a key can't hide in a duplicate.
            if (key <= lastKey || valueEnd > rdEnd) return BAD
            if (key == KEY_MANDATORY) {
                if (len == 0 || len % 2 != 0) return BAD
                var q = p + 4
                while (q < valueEnd) { if (u16(m, q) == KEY_ECH) mandatoryNamesEch = true; q += 2 }
            }
            if (key == KEY_ECH) ech = p
            lastKey = key
            p = valueEnd
        }
        // Without ech the record would break its own "mandatory" list (§8): remove it.
        if (mandatoryNamesEch) return BAD
        return ech
    }

    /** Names in RDATA that may be compressed, so their pointers are seen. False: malformed. */
    private fun walkRdataNames(m: ByteArray, type: Int, rdStart: Int, rdEnd: Int, pointers: IntList): Boolean {
        fun name(at: Int): Int {
            if (at >= rdEnd) return -1
            val end = walkName(m, at, pointers)
            return if (end in 0..rdEnd) end else -1
        }
        return when (type) {
            TYPE_NS, TYPE_CNAME, TYPE_PTR, TYPE_DNAME -> name(rdStart) >= 0
            TYPE_MX -> name(rdStart + 2) >= 0
            TYPE_SRV -> name(rdStart + 6) >= 0
            TYPE_SOA -> { val a = name(rdStart); a >= 0 && name(a) >= 0 }
            else -> true
        }
    }

    /**
     * Walks a name at [start]; returns the position after it in the stream, or -1. Pointers
     * must point backwards (no loops), labels ≤ 63, names ≤ 255, at most [MAX_JUMPS] hops.
     * The in-stream pointer, if any, is added to [pointers].
     */
    private fun walkName(m: ByteArray, start: Int, pointers: IntList?): Int {
        var p = start
        var end = -1
        var jumps = 0
        var nameLen = 0
        while (true) {
            if (p >= m.size) return -1
            val len = m[p].toInt() and 0xFF
            when {
                len == 0 -> return if (end >= 0) end else p + 1
                len and 0xC0 == 0xC0 -> {
                    if (p + 1 >= m.size) return -1
                    val target = ((len and 0x3F) shl 8) or (m[p + 1].toInt() and 0xFF)
                    if (target >= p || ++jumps > MAX_JUMPS) return -1
                    if (end < 0) { end = p + 2; pointers?.add(p) }
                    p = target
                }
                len and 0xC0 != 0 -> return -1                        // reserved label types
                else -> {
                    nameLen += len + 1
                    if (nameLen > MAX_NAME) return -1
                    p += 1 + len
                }
            }
        }
    }

    /** Bytes removed before [pos]. */
    private fun shift(removed: IntList, pos: Int): Int {
        var s = 0
        for (i in 0 until removed.size step 2) if (removed[i + 1] <= pos) s += removed[i + 1] - removed[i]
        return s
    }

    private fun removedWithin(removed: IntList, from: Int, to: Int): Int {
        var s = 0
        for (i in 0 until removed.size step 2) if (removed[i] >= from && removed[i + 1] <= to) s += removed[i + 1] - removed[i]
        return s
    }

    private fun insideRemoved(removed: IntList, pos: Int): Boolean {
        for (i in 0 until removed.size step 2) if (pos >= removed[i] && pos < removed[i + 1]) return true
        return false
    }

    private fun u16(m: ByteArray, at: Int) = ((m[at].toInt() and 0xFF) shl 8) or (m[at + 1].toInt() and 0xFF)
    private fun put16(m: ByteArray, at: Int, v: Int) { m[at] = (v ushr 8).toByte(); m[at + 1] = v.toByte() }

    /** A growable int array: a DNS answer has few records, but the count is untrusted. */
    private class IntList {
        private var a = IntArray(16)
        var size = 0
            private set
        fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        operator fun get(i: Int) = a[i]
        fun truncate(n: Int) { size = n }
    }
}
