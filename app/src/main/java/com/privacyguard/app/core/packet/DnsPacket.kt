/**
 * DnsPacket.kt
 *
 * RFC 1034 - Domain Names - Concepts and Facilities
 * RFC 1035 - Domain Names - Implementation and Specification
 *
 * Parses DNS queries and responses from UDP payload (port 53).
 * DNS is the PRIMARY interception point for PrivacyGuard's blocking feature.
 *
 * Why DNS blocking is the most effective:
 * 1. Every connection starts with a DNS lookup
 * 2. Blocking at DNS layer prevents the connection entirely
 * 3. No bandwidth wasted on blocked requests
 * 4. Works for all protocols (HTTP, HTTPS, FTP, etc.)
 * 5. Simple NXDOMAIN response is universally understood
 *
 * DNS Message Format (RFC 1035 section 4.1):
 *
 *    0  1  2  3  4  5  6  7  8  9  0  1  2  3  4  5
 *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 *   |                      ID                         |
 *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 *   |QR|   Opcode  |AA|TC|RD|RA| Z|AD|CD|   RCODE   |
 *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 *   |                    QDCOUNT                      |
 *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 *   |                    ANCOUNT                      |
 *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 *   |                    NSCOUNT                      |
 *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 *   |                    ARCOUNT                      |
 *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 *   |                   QUESTION                      |
 *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 *   |                    ANSWER                       |
 *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 *   |                   AUTHORITY                     |
 *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 *   |                   ADDITIONAL                    |
 *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 *
 * Performance requirements:
 * - Parse must complete in < 50 microseconds
 * - Called for every DNS packet (10-15% of traffic)
 * - Domain name parsing is the most expensive operation
 *
 * Thread Safety: This class is immutable. All methods are thread-safe.
 *
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.core.packet

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DNS Resource Record Types (RFC 1035 section 3.2.2)
 */
object DnsType {
    const val A = 1          // IPv4 address
    const val NS = 2         // Name server
    const val CNAME = 5      // Canonical name (alias)
    const val SOA = 6        // Start of authority
    const val PTR = 12       // Pointer (reverse lookup)
    const val MX = 15        // Mail exchange
    const val TXT = 16       // Text string
    const val AAAA = 28      // IPv6 address
    const val SRV = 33       // Service locator
    const val OPT = 41       // EDNS0 option (pseudo-RR)
    const val ANY = 255      // Any type (meta-query)

    fun toString(type: Int): String = when (type) {
        A -> "A"
        NS -> "NS"
        CNAME -> "CNAME"
        SOA -> "SOA"
        PTR -> "PTR"
        MX -> "MX"
        TXT -> "TXT"
        AAAA -> "AAAA"
        SRV -> "SRV"
        OPT -> "OPT"
        ANY -> "ANY"
        else -> "TYPE$type"
    }
}

/**
 * DNS Resource Record Classes (RFC 1035 section 3.2.4)
 */
object DnsClass {
    const val IN = 1         // Internet
    const val CH = 3         // Chaos (rare)
    const val HS = 4         // Hesiod (rare)
    const val ANY = 255      // Any class (meta-query)

    fun toString(clazz: Int): String = when (clazz) {
        IN -> "IN"
        CH -> "CH"
        HS -> "HS"
        ANY -> "ANY"
        else -> "CLASS$clazz"
    }
}

/**
 * DNS Response Codes (RCODE) - RFC 1035 section 4.1.1
 */
object DnsRcode {
    const val NO_ERROR = 0      // Success
    const val FORMAT_ERROR = 1  // Query malformed
    const val SERVER_FAILURE = 2 // Server failed
    const val NAME_ERROR = 3     // NXDOMAIN - Domain does not exist (BLOCK!)
    const val NOT_IMPLEMENTED = 4
    const val REFUSED = 5

    fun toString(rcode: Int): String = when (rcode) {
        NO_ERROR -> "NO_ERROR"
        FORMAT_ERROR -> "FORMAT_ERROR"
        SERVER_FAILURE -> "SERVER_FAILURE"
        NAME_ERROR -> "NXDOMAIN"
        NOT_IMPLEMENTED -> "NOT_IMPLEMENTED"
        REFUSED -> "REFUSED"
        else -> "RCODE$rcode"
    }
}

/**
 * DNS Header (12 bytes fixed)
 *
 * @property id Unique identifier matching query with response (16 bits)
 * @property flags 16-bit flags (QR, Opcode, AA, TC, RD, RA, Z, AD, CD, RCODE)
 * @property questionCount Number of questions (QDCOUNT)
 * @property answerCount Number of answers (ANCOUNT)
 * @property authorityCount Number of authority records (NSCOUNT)
 * @property additionalCount Number of additional records (ARCOUNT)
 */
data class DnsHeader(
    val id: Int,
    val flags: Int,
    val questionCount: Int,
    val answerCount: Int,
    val authorityCount: Int,
    val additionalCount: Int
) {

    /**
     * Returns true if this is a query (QR = 0)
     */
    fun isQuery(): Boolean = (flags shr 15) and 0x01 == 0

    /**
     * Returns true if this is a response (QR = 1)
     */
    fun isResponse(): Boolean = !isQuery()

    /**
     * Returns the Opcode (4 bits, standard query = 0)
     */
    fun opcode(): Int = (flags shr 11) and 0x0F

    /**
     * Returns true if this is a standard query (Opcode = 0)
     */
    fun isStandardQuery(): Boolean = opcode() == 0

    /**
     * Returns true if Authoritative Answer (AA) flag is set
     */
    fun isAuthoritative(): Boolean = (flags shr 10) and 0x01 != 0

    /**
     * Returns true if Truncation (TC) flag is set (message truncated)
     */
    fun isTruncated(): Boolean = (flags shr 9) and 0x01 != 0

    /**
     * Returns true if Recursion Desired (RD) flag is set
     */
    fun isRecursionDesired(): Boolean = (flags shr 8) and 0x01 != 0

    /**
     * Returns true if Recursion Available (RA) flag is set
     */
    fun isRecursionAvailable(): Boolean = (flags shr 7) and 0x01 != 0

    /**
     * Returns true if Authentic Data (AD) flag is set (DNSSEC)
     */
    fun isAuthenticData(): Boolean = (flags shr 5) and 0x01 != 0

    /**
     * Returns true if Checking Disabled (CD) flag is set (DNSSEC)
     */
    fun isCheckingDisabled(): Boolean = (flags shr 4) and 0x01 != 0

    /**
     * Returns the Response Code (RCODE, 4 bits)
     */
    fun rcode(): Int = flags and 0x0F

    /**
     * Returns true if this is a successful response (RCODE = 0)
     */
    fun isSuccess(): Boolean = rcode() == DnsRcode.NO_ERROR

    /**
     * Returns true if this is NXDOMAIN (domain does not exist)
     */
    fun isNxDomain(): Boolean = rcode() == DnsRcode.NAME_ERROR

    /**
     * Creates a response header for the given query
     */
    fun toResponseHeader(rcode: Int = DnsRcode.NO_ERROR, authoritative: Boolean = false): DnsHeader {
        var newFlags = (flags and 0x7FFF) or 0x8000  // Set QR bit (response)
        newFlags = newFlags and 0xFFF0 or (rcode and 0x0F)  // Set RCODE

        if (authoritative) {
            newFlags = newFlags or (1 shl 10)  // Set AA bit
        }

        return DnsHeader(
            id = id,
            flags = newFlags,
            questionCount = questionCount,
            answerCount = 0,
            authorityCount = 0,
            additionalCount = 0
        )
    }

    /**
     * Creates an NXDOMAIN response header
     */
    fun toNxDomainHeader(): DnsHeader {
        return toResponseHeader(rcode = DnsRcode.NAME_ERROR, authoritative = true)
    }

    companion object {
        /**
         * Parses DNS header from raw bytes
         *
         * @param buffer ByteBuffer positioned at start of DNS header
         * @return DnsHeader or null if insufficient bytes
         */
        fun parse(buffer: ByteBuffer): DnsHeader? {
            if (buffer.remaining() < 12) return null

            val id = buffer.getShort().toInt() and 0xFFFF
            val flags = buffer.getShort().toInt() and 0xFFFF
            val qdcount = buffer.getShort().toInt() and 0xFFFF
            val ancount = buffer.getShort().toInt() and 0xFFFF
            val nscount = buffer.getShort().toInt() and 0xFFFF
            val arcount = buffer.getShort().toInt() and 0xFFFF

            return DnsHeader(id, flags, qdcount, ancount, nscount, arcount)
        }

        /**
         * Creates a query header
         */
        fun createQueryHeader(id: Int = (System.currentTimeMillis() and 0xFFFF).toInt()): DnsHeader {
            // Standard query: QR=0, Opcode=0, RD=1
            val flags = 0x0100  // RD bit set
            return DnsHeader(id, flags, 1, 0, 0, 0)
        }
    }
}

/**
 * DNS Question section (variable length)
 *
 * @property domainName The domain being queried (e.g., "google.com")
 * @property queryType Record type (1 = A, 28 = AAAA, 15 = MX, etc.)
 * @property queryClass Class (1 = IN for internet)
 */
data class DnsQuestion(
    val domainName: String,
    val queryType: Int,
    val queryClass: Int
) {

    fun isARecord(): Boolean = queryType == DnsType.A

    fun isAaaaRecord(): Boolean = queryType == DnsType.AAAA

    fun isAnyRecord(): Boolean = queryType == DnsType.ANY

    override fun toString(): String {
        return "$domainName ${DnsClass.toString(queryClass)} ${DnsType.toString(queryType)}"
    }

    companion object {
        /**
         * Parses DNS question from raw bytes
         *
         * Domain names are encoded as:
         * - Sequence of labels, each with length byte followed by that many bytes
         * - Terminated by a zero byte
         * - Maximum 255 bytes total
         * - Example: "google.com" = 0x06 'g' 'o' 'o' 'g' 'l' 'e' 0x03 'c' 'o' 'm' 0x00
         *
         * Supports compression pointers (RFC 1035 section 4.1.4)
         *
         * @param buffer ByteBuffer positioned at start of question
         * @return DnsQuestion or null if parsing fails
         */
        fun parse(buffer: ByteBuffer): DnsQuestion? {
            if (buffer.remaining() < 5) return null  // Minimum: root domain (1 byte) + type + class

            val domainName = parseDomainName(buffer) ?: return null

            if (buffer.remaining() < 4) return null
            val queryType = buffer.getShort().toInt() and 0xFFFF
            val queryClass = buffer.getShort().toInt() and 0xFFFF

            return DnsQuestion(domainName, queryType, queryClass)
        }

        /**
         * Parses a domain name from DNS wire format.
         *
         * Format:
         * - Each label: length byte (0-63) followed by that many bytes
         * - Terminated by a zero-length label
         * - Compression pointer: two high bits set, followed by offset (14 bits)
         *
         * @param buffer ByteBuffer positioned at start of domain name
         * @param visitedOffsets Set of offsets already visited (prevents infinite loops)
         * @return Domain name as string or null if invalid
         */
        private fun parseDomainName(
            buffer: ByteBuffer,
            visitedOffsets: MutableSet<Int> = mutableSetOf()
        ): String? {
            val result = StringBuilder()
            val startPos = buffer.position()
            var maxLoops = 255  // Prevent infinite loops

            while (maxLoops-- > 0) {
                if (buffer.remaining() < 1) return null
                val labelLength = buffer.get().toInt() and 0xFF

                if (labelLength == 0) {
                    // End of domain name
                    break
                }

                if ((labelLength and 0xC0) == 0xC0) {
                    // Compression pointer (RFC 1035 section 4.1.4)
                    if (buffer.remaining() < 1) return null
                    val secondByte = buffer.get().toInt() and 0xFF
                    val pointer = ((labelLength and 0x3F) shl 8) or secondByte

                    // Prevent infinite loops
                    if (visitedOffsets.contains(pointer)) {
                        return null
                    }
                    visitedOffsets.add(pointer)

                    // Save current position
                    val currentPos = buffer.position()

                    // Jump to pointer location
                    buffer.position(startPos + pointer)

                    // Recursively parse the referenced name
                    val referencedName = parseDomainName(buffer, visitedOffsets)
                    if (referencedName == null) {
                        return null
                    }
                    result.append(referencedName)

                    // Restore position to after the pointer
                    buffer.position(currentPos)

                    // Compression pointer marks the end of the name
                    break
                }

                // Normal label
                if (labelLength > 63) return null  // Maximum label length is 63
                if (buffer.remaining() < labelLength) return null

                val labelBytes = ByteArray(labelLength)
                buffer.get(labelBytes)
                val label = String(labelBytes, Charsets.US_ASCII)

                // Validate label characters (RFC 1035 allows A-Z, a-z, 0-9, hyphen)
                if (!isValidLabel(label)) {
                    return null
                }

                if (result.isNotEmpty()) {
                    result.append('.')
                }
                result.append(label)
            }

            return result.toString().ifEmpty { null }
        }

        /**
         * Validates a DNS label (RFC 1035 section 2.3.1)
         * - Letters (A-Z, a-z)
         * - Digits (0-9)
         * - Hyphen (-)
         * - First and last characters cannot be hyphen
         */
        private fun isValidLabel(label: String): Boolean {
            if (label.isEmpty() || label.length > 63) return false
            if (label.first() == '-' || label.last() == '-') return false

            return label.all { c ->
                c.isLetterOrDigit() || c == '-'
            }
        }

        /**
         * Encodes a domain name into DNS wire format.
         *
         * Example: "google.com" -> [6] 'g' 'o' 'o' 'g' 'l' 'e' [3] 'c' 'o' 'm' [0]
         *
         * @param domain Domain name as string
         * @return Byte array in DNS wire format
         */
        fun encodeDomainName(domain: String): ByteArray {
            val labels = domain.split('.')
            var totalSize = labels.sumOf { it.length + 1 } + 1  // +1 for each label length, +1 for terminator
            val buffer = ByteBuffer.allocate(totalSize)

            for (label in labels) {
                buffer.put(label.length.toByte())
                buffer.put(label.toByteArray(Charsets.US_ASCII))
            }
            buffer.put(0)

            val result = ByteArray(buffer.position())
            buffer.rewind()
            buffer.get(result)
            return result
        }
    }
}

/**
 * DNS Resource Record (Answer, Authority, Additional sections)
 *
 * @property name Domain name for this record
 * @property type Record type (A, AAAA, CNAME, etc.)
 * @property clazz Record class (IN, CH, HS)
 * @property ttl Time To Live in seconds
 * @property dataLength Length of RDATA in bytes
 * @property data Record data (IP address, domain name, etc.)
 */
data class DnsResourceRecord(
    val name: String,
    val type: Int,
    val clazz: Int,
    val ttl: Int,
    val dataLength: Int,
    val data: ByteArray
) {

    /**
     * Returns the data as IPv4 address string (for A records)
     */
    fun asIpv4Address(): String? {
        if (type != DnsType.A || data.size != 4) return null
        return "${data[0].toInt() and 0xFF}.${data[1].toInt() and 0xFF}.${data[2].toInt() and 0xFF}.${data[3].toInt() and 0xFF}"
    }

    /**
     * Returns the data as domain name (for CNAME, NS, MX records)
     */
    fun asDomainName(): String? {
        if (type !in setOf(DnsType.CNAME, DnsType.NS, DnsType.MX, DnsType.PTR)) return null
        // Simplified: assume data is a domain name without compression
        return String(data, Charsets.US_ASCII).trimEnd('\u0000')
    }

    override fun toString(): String {
        return "$name ${DnsClass.toString(clazz)} ${DnsType.toString(type)} TTL=$ttl"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DnsResourceRecord) return false

        if (name != other.name) return false
        if (type != other.type) return false
        if (clazz != other.clazz) return false
        if (ttl != other.ttl) return false
        if (dataLength != other.dataLength) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + type
        result = 31 * result + clazz
        result = 31 * result + ttl
        result = 31 * result + dataLength
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * Complete DNS message (query or response)
 *
 * This class is immutable - all modifications create new instances.
 *
 * @property header DNS header
 * @property questions List of questions (usually 1)
 * @property answers List of answer records
 * @property authorities List of authority records
 * @property additionals List of additional records
 * @property rawData Original raw bytes (for caching)
 */
class DnsMessage private constructor(
    val header: DnsHeader,
    val questions: List<DnsQuestion>,
    val answers: List<DnsResourceRecord>,
    val authorities: List<DnsResourceRecord>,
    val additionals: List<DnsResourceRecord>,
    val rawData: ByteArray
) {

    /**
     * Returns the first question's domain name (most common case)
     */
    fun getPrimaryDomain(): String? = questions.firstOrNull()?.domainName

    /**
     * Returns true if this is a query (not a response)
     */
    fun isQuery(): Boolean = header.isQuery()

    /**
     * Returns true if this is a response
     */
    fun isResponse(): Boolean = header.isResponse()

    /**
     * Returns true if this is a standard query
     */
    fun isStandardQuery(): Boolean = header.isStandardQuery()

    /**
     * Returns true if this is an NXDOMAIN response
     */
    fun isNxDomain(): Boolean = header.isNxDomain()

    /**
     * Creates a response for this query (echoes back the question)
     */
    fun toResponse(answers: List<DnsResourceRecord> = emptyList()): DnsMessage {
        val responseHeader = header.toResponseHeader()

        return DnsMessage(
            header = responseHeader,
            questions = questions,
            answers = answers,
            authorities = emptyList(),
            additionals = emptyList(),
            rawData = ByteArray(0)  // Will be regenerated on toRawBytes()
        )
    }

    /**
     * Creates an NXDOMAIN response for a blocked domain
     */
    fun toNxDomainResponse(): DnsMessage {
        val nxHeader = header.toNxDomainHeader()

        return DnsMessage(
            header = nxHeader,
            questions = questions,
            answers = emptyList(),
            authorities = emptyList(),
            additionals = emptyList(),
            rawData = ByteArray(0)
        )
    }

    /**
     * Converts the DNS message back to raw bytes
     */
    fun toRawBytes(): ByteArray {
        // Calculate total size
        var totalSize = 12  // Header

        // Questions section
        val questionBuffers = mutableListOf<ByteArray>()
        for (question in questions) {
            val domainBytes = DnsQuestion.encodeDomainName(question.domainName)
            val typeAndClass = ByteArray(4)
            val qBuffer = ByteBuffer.wrap(typeAndClass)
            qBuffer.order(ByteOrder.BIG_ENDIAN)
            qBuffer.putShort(question.queryType.toShort())
            qBuffer.putShort(question.queryClass.toShort())

            val questionBytes = domainBytes + typeAndClass
            questionBuffers.add(questionBytes)
            totalSize += questionBytes.size
        }

        // Answers section (simplified for MVP)
        val answerBuffers = mutableListOf<ByteArray>()
        for (answer in answers) {
            val nameBytes = DnsQuestion.encodeDomainName(answer.name)
            val rrHeader = ByteArray(10)
            val rrBuffer = ByteBuffer.wrap(rrHeader)
            rrBuffer.order(ByteOrder.BIG_ENDIAN)
            rrBuffer.putShort(answer.type.toShort())
            rrBuffer.putShort(answer.clazz.toShort())
            rrBuffer.putInt(answer.ttl)
            rrBuffer.putShort(answer.dataLength.toShort())

            val answerBytes = nameBytes + rrHeader + answer.data
            answerBuffers.add(answerBytes)
            totalSize += answerBytes.size
        }

        // Build final packet
        val buffer = ByteBuffer.allocate(totalSize)
        buffer.order(ByteOrder.BIG_ENDIAN)

        // Header
        buffer.putShort(header.id.toShort())
        buffer.putShort(header.flags.toShort())
        buffer.putShort(header.questionCount.toShort())
        buffer.putShort(header.answerCount.toShort())
        buffer.putShort(header.authorityCount.toShort())
        buffer.putShort(header.additionalCount.toShort())

        // Questions
        for (questionBytes in questionBuffers) {
            buffer.put(questionBytes)
        }

        // Answers
        for (answerBytes in answerBuffers) {
            buffer.put(answerBytes)
        }

        return buffer.array()
    }

    override fun toString(): String {
        return "DnsMessage(id=${header.id}, q=${questions.size}, a=${answers.size}, " +
                "rcode=${DnsRcode.toString(header.rcode())}, domain=${getPrimaryDomain()})"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DnsMessage) return false

        if (header != other.header) return false
        if (questions != other.questions) return false
        if (answers != other.answers) return false
        if (authorities != other.authorities) return false
        if (additionals != other.additionals) return false
        if (!rawData.contentEquals(other.rawData)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + questions.hashCode()
        result = 31 * result + answers.hashCode()
        result = 31 * result + authorities.hashCode()
        result = 31 * result + additionals.hashCode()
        result = 31 * result + rawData.contentHashCode()
        return result
    }

    companion object {

        /**
         * Parses a complete DNS message from raw UDP payload
         *
         * @param data Raw bytes from UDP packet (starting at DNS header)
         * @param length Number of valid bytes
         * @return DnsMessage or null if parsing fails
         */
        fun parse(data: ByteArray, length: Int): DnsMessage? {
            if (length < 12) return null

            val buffer = ByteBuffer.wrap(data, 0, length)
            buffer.order(ByteOrder.BIG_ENDIAN)

            val header = DnsHeader.parse(buffer) ?: return null

            val questions = mutableListOf<DnsQuestion>()
            for (i in 0 until header.questionCount) {
                val question = DnsQuestion.parse(buffer) ?: return null
                questions.add(question)
            }

            // For MVP, we don't parse answers/authority/additional sections
            // Full parsing will be added in v1.5 if needed
            // For now, we only need questions for blocking decisions

            return DnsMessage(
                header = header,
                questions = questions,
                answers = emptyList(),
                authorities = emptyList(),
                additionals = emptyList(),
                rawData = data.copyOf(length)
            )
        }

        /**
         * Creates a simple query message for testing
         */
        fun createQuery(domain: String, queryType: Int = DnsType.A): DnsMessage {
            val header = DnsHeader.createQueryHeader()
            val question = DnsQuestion(domain, queryType, DnsClass.IN)

            return DnsMessage(
                header = header,
                questions = listOf(question),
                answers = emptyList(),
                authorities = emptyList(),
                additionals = emptyList(),
                rawData = ByteArray(0)
            )
        }
    }
}