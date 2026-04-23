package com.privacyguard.core.packet

import com.privacyguard.core.utils.ByteUtils

/**
 * Represents a parsed DNS message (query or response).
 *
 * DNS wire format (RFC 1035):
 * ```
 *  0  1  2  3  4  5  6  7  8  9  A  B  C  D  E  F
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 * |                      ID                       |
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 * |QR|   Opcode  |AA|TC|RD|RA|   Z    |   RCODE   |
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 * |                    QDCOUNT                    |
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 * |                    ANCOUNT                    |
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 * |                    NSCOUNT                    |
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 * |                    ARCOUNT                    |
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 * ```
 */
data class DnsPacket(
    /** Transaction ID (matches query to response). */
    val id: Int,
    /** QR flag: false = query, true = response. */
    val isResponse: Boolean,
    /** Opcode (0 = QUERY, 1 = IQUERY, 2 = STATUS). */
    val opcode: Int,
    /** Authoritative Answer (valid in responses only). */
    val isAuthoritative: Boolean,
    /** Truncated flag. */
    val isTruncated: Boolean,
    /** Recursion Desired (set by client). */
    val recursionDesired: Boolean,
    /** Recursion Available (set by server). */
    val recursionAvailable: Boolean,
    /** Response code (0 = no error, 3 = NXDOMAIN, …). */
    val responseCode: Int,
    /** Question section. */
    val questions: List<DnsQuestion>,
    /** Answer section. */
    val answers: List<DnsRecord>,
    /** Authority section. */
    val authority: List<DnsRecord>,
    /** Additional section. */
    val additional: List<DnsRecord>,
) {
    // ─────────────────────────────────────────────────────────────────────────
    // Derived Properties
    // ─────────────────────────────────────────────────────────────────────────

    /** True if this is a standard query (not a response). */
    val isQuery: Boolean get() = !isResponse

    /**
     * The first question's name, or null if [questions] is empty.
     * For most DNS queries there is exactly one question.
     */
    val queryName: String? get() = questions.firstOrNull()?.name

    /** Returns all A-record IPv4 addresses from the answer section. */
    val aRecords: List<String>
        get() = answers.filter { it.type == DnsRecord.TYPE_A && it.rdata.size == 4 }
                       .map { "${it.rdata[0].toInt() and 0xFF}.${it.rdata[1].toInt() and 0xFF}." +
                              "${it.rdata[2].toInt() and 0xFF}.${it.rdata[3].toInt() and 0xFF}" }

    /** Returns all CNAME targets from the answer section. */
    val cnameRecords: List<String>
        get() = answers.filter { it.type == DnsRecord.TYPE_CNAME }.mapNotNull { it.rdataName }

    override fun toString(): String {
        val qType = if (isQuery) "QUERY" else "RESP(${responseCode})"
        val name  = queryName ?: "(no question)"
        return "DnsPacket(id=0x${id.toString(16).uppercase()} $qType $name " +
               "questions=${questions.size} answers=${answers.size})"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Companion — Factory, Constants, & Parser
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        private const val HEADER_LENGTH = 12

        /**
         * Parses a raw DNS message from [data].
         * @return parsed [DnsPacket], or null if the data is too short or malformed.
         */
        fun parse(data: ByteArray): DnsPacket? {
            if (data.size < HEADER_LENGTH) return null

            val id    = ByteUtils.readUInt16(data, 0)
            val flags = ByteUtils.readUInt16(data, 2)

            val isResponse         = (flags and 0x8000) != 0
            val opcode             = (flags ushr 11) and 0x0F
            val isAuthoritative    = (flags and 0x0400) != 0
            val isTruncated        = (flags and 0x0200) != 0
            val recursionDesired   = (flags and 0x0100) != 0
            val recursionAvailable = (flags and 0x0080) != 0
            val responseCode       = flags and 0x000F

            val qdCount = ByteUtils.readUInt16(data, 4)
            val anCount = ByteUtils.readUInt16(data, 6)
            val nsCount = ByteUtils.readUInt16(data, 8)
            val arCount = ByteUtils.readUInt16(data, 10)

            var offset = HEADER_LENGTH

            // ── Question Section ────────────────────────────────────────────
            val questions = mutableListOf<DnsQuestion>()
            repeat(qdCount) {
                val (name, newOffset) = readName(data, offset) ?: return null
                if (newOffset + 4 > data.size) return null
                val type  = ByteUtils.readUInt16(data, newOffset)
                val clazz = ByteUtils.readUInt16(data, newOffset + 2)
                questions += DnsQuestion(name, type, clazz)
                offset = newOffset + 4
            }

            // ── Record Sections ─────────────────────────────────────────────
            fun readRecords(count: Int): List<DnsRecord>? {
                val records = mutableListOf<DnsRecord>()
                repeat(count) {
                    val (name, off2) = readName(data, offset) ?: return null
                    if (off2 + 10 > data.size) return null
                    val type  = ByteUtils.readUInt16(data, off2)
                    val clazz = ByteUtils.readUInt16(data, off2 + 2)
                    val ttl   = ByteUtils.readInt32(data,  off2 + 4)
                    val rdLen = ByteUtils.readUInt16(data, off2 + 8)
                    val rdEnd = off2 + 10 + rdLen
                    if (rdEnd > data.size) return null
                    val rdata     = data.copyOfRange(off2 + 10, rdEnd)
                    val rdataName = if (type == DnsRecord.TYPE_CNAME || type == DnsRecord.TYPE_NS ||
                                        type == DnsRecord.TYPE_PTR)
                        readName(data, off2 + 10)?.first else null
                    records += DnsRecord(name, type, clazz, ttl, rdata, rdataName)
                    offset = rdEnd
                }
                return records
            }

            val answers    = readRecords(anCount)    ?: return null
            val authority  = readRecords(nsCount)    ?: return null
            val additional = readRecords(arCount)    ?: return null

            return DnsPacket(
                id                 = id,
                isResponse         = isResponse,
                opcode             = opcode,
                isAuthoritative    = isAuthoritative,
                isTruncated        = isTruncated,
                recursionDesired   = recursionDesired,
                recursionAvailable = recursionAvailable,
                responseCode       = responseCode,
                questions          = questions,
                answers            = answers,
                authority          = authority,
                additional         = additional,
            )
        }

        /**
         * Reads a DNS name (label sequence) starting at [offset] in [data],
         * handling RFC 1035 pointer compression (0xC0 prefix).
         *
         * @return Pair(decoded name, offset past the name in the original stream),
         *         or null on error.
         */
        private fun readName(data: ByteArray, offset: Int): Pair<String, Int>? {
            val labels  = mutableListOf<String>()
            var pos     = offset
            var jumped  = false
            var endPos  = -1
            var jumps   = 0

            while (pos < data.size) {
                val len = data[pos].toInt() and 0xFF

                when {
                    len == 0 -> {
                        // End of name
                        if (!jumped) endPos = pos + 1
                        break
                    }
                    (len and 0xC0) == 0xC0 -> {
                        // Pointer compression
                        if (pos + 1 >= data.size) return null
                        if (!jumped) endPos = pos + 2
                        val pointer = ((len and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                        if (pointer >= data.size) return null
                        pos    = pointer
                        jumped = true
                        if (++jumps > 10) return null   // loop guard
                    }
                    else -> {
                        // Regular label
                        val end = pos + 1 + len
                        if (end > data.size) return null
                        labels += String(data, pos + 1, len, Charsets.US_ASCII)
                        pos = end
                    }
                }
            }

            if (endPos == -1) endPos = pos + 1
            return Pair(labels.joinToString("."), endPos)
        }

        /**
         * Encodes a domain name into DNS wire-format labels.
         * e.g. "example.com" → [7,'e','x','a','m','p','l','e', 3,'c','o','m', 0]
         */
        fun encodeName(name: String): ByteArray {
            val cleanName = name.trimEnd('.')
            val parts     = cleanName.split('.')
            val out       = mutableListOf<Byte>()
            for (part in parts) {
                out.add(part.length.toByte())
                out.addAll(part.toByteArray(Charsets.US_ASCII).toList())
            }
            out.add(0)   // root label
            return out.toByteArray()
        }

        /**
         * Builds a DNS A/AAAA query for [name].
         *
         * @param name   domain to query, e.g. "example.com".
         * @param id     transaction ID (random if not provided).
         * @param typeA  true = query for A records, false = query for AAAA.
         * @return raw DNS query bytes ready to send over UDP.
         */
        fun buildQuery(name: String, id: Int, typeA: Boolean = true): ByteArray {
            val encodedName = encodeName(name)
            val buf         = ByteArray(HEADER_LENGTH + encodedName.size + 4)

            ByteUtils.writeUInt16(buf, 0, id)
            ByteUtils.writeUInt16(buf, 2, 0x0100)   // RD flag set
            ByteUtils.writeUInt16(buf, 4, 1)         // QDCOUNT = 1
            ByteUtils.writeUInt16(buf, 6, 0)
            ByteUtils.writeUInt16(buf, 8, 0)
            ByteUtils.writeUInt16(buf, 10, 0)

            encodedName.copyInto(buf, HEADER_LENGTH)
            val qTypeOffset = HEADER_LENGTH + encodedName.size
            ByteUtils.writeUInt16(buf, qTypeOffset,     if (typeA) DnsRecord.TYPE_A else DnsRecord.TYPE_AAAA)
            ByteUtils.writeUInt16(buf, qTypeOffset + 2, DnsRecord.CLASS_IN)
            return buf
        }

        /**
         * Builds a DNS NXDOMAIN (REFUSED / blocked) response for [query].
         * Used by the DNS intercept layer to silently block domains.
         */
        fun buildBlockedResponse(query: DnsPacket): ByteArray {
            if (query.questions.isEmpty()) return ByteArray(0)

            val encodedName = encodeName(query.questions[0].name)
            val buf         = ByteArray(HEADER_LENGTH + encodedName.size + 4)

            ByteUtils.writeUInt16(buf, 0, query.id)
            // QR=1, AA=0, TC=0, RD=1, RA=1, RCODE=3 (NXDOMAIN)
            ByteUtils.writeUInt16(buf, 2, 0x8183)
            ByteUtils.writeUInt16(buf, 4, 1)    // QDCOUNT = 1 (echo question)
            ByteUtils.writeUInt16(buf, 6, 0)
            ByteUtils.writeUInt16(buf, 8, 0)
            ByteUtils.writeUInt16(buf, 10, 0)

            encodedName.copyInto(buf, HEADER_LENGTH)
            val off = HEADER_LENGTH + encodedName.size
            ByteUtils.writeUInt16(buf, off,     query.questions[0].type)
            ByteUtils.writeUInt16(buf, off + 2, query.questions[0].clazz)
            return buf
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Supporting Data Classes
// ─────────────────────────────────────────────────────────────────────────────

/** A single entry in the DNS question section. */
data class DnsQuestion(
    val name:  String,
    val type:  Int,
    val clazz: Int,
) {
    override fun toString(): String = "Question($name type=$type class=$clazz)"
}

/** A single resource record (answer / authority / additional section). */
data class DnsRecord(
    val name:      String,
    val type:      Int,
    val clazz:     Int,
    /** Time-To-Live in seconds. */
    val ttl:       Int,
    /** Raw RDATA bytes. */
    val rdata:     ByteArray,
    /** Decoded name from RDATA (for CNAME / NS / PTR records). */
    val rdataName: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DnsRecord) return false
        return name == other.name && type == other.type && rdata.contentEquals(other.rdata)
    }
    override fun hashCode(): Int = 31 * (31 * name.hashCode() + type) + rdata.contentHashCode()
    override fun toString(): String = "DnsRecord($name type=$type ttl=$ttl rdataLen=${rdata.size})"

    companion object {
        const val TYPE_A     = 1
        const val TYPE_NS    = 2
        const val TYPE_CNAME = 5
        const val TYPE_SOA   = 6
        const val TYPE_PTR   = 12
        const val TYPE_MX    = 15
        const val TYPE_TXT   = 16
        const val TYPE_AAAA  = 28
        const val TYPE_SRV   = 33
        const val CLASS_IN   = 1
    }
}