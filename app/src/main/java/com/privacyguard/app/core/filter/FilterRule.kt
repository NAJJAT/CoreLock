package com.privacyguard.core.filter

import com.privacyguard.core.metadata.EncryptionStatus

/**
 * A single traffic-filtering rule.
 *
 * Updated strategy — rules now cover four concerns:
 *  1. **Metadata blocking** — block by app / domain / IP / port
 *  2. **DNS blocking**      — block at DNS query time
 *  3. **Encryption enforcement** — deny cleartext or weak-TLS connections
 *  4. **Allow overrides**   — whitelist exceptions
 *
 * No MITM / payload inspection is ever performed.
 * All matching is based on observable metadata (port, protocol, hostname from SNI/DNS).
 */
data class FilterRule(
    val id:        String,
    val label:     String,
    val action:    Action,
    val source:    Source,
    val priority:  Int     = DEFAULT_PRIORITY,
    val isEnabled: Boolean = true,

    // ── Match criteria (all non-null must match simultaneously) ─────────────
    val matchUid:      Int?    = null,
    val matchPackage:  String? = null,
    /** Exact or wildcard domain, e.g. "example.com" or "*.example.com". */
    val matchDomain:   String? = null,
    /** Exact IP or CIDR, e.g. "1.2.3.4" or "10.0.0.0/8". */
    val matchIp:       String? = null,
    val matchPort:     Int?    = null,
    val matchProtocol: Protocol? = null,
    /**
     * If set, the rule fires only for connections with this encryption status.
     * Use [EncryptionStatus.CLEARTEXT] to block all unencrypted traffic.
     */
    val matchEncryption: EncryptionStatus? = null,
    /** If true, rule only applies when the owning app is in the background. */
    val matchBackground: Boolean? = null,
) {
    // ─────────────────────────────────────────────────────────────────────────
    // Enums
    // ─────────────────────────────────────────────────────────────────────────

    enum class Action { ALLOW, DENY }

    enum class Source {
        USER,        // manually created by the user
        BLOCKLIST,   // from EasyList / OISD etc.
        COMMUNITY,   // from community threat-intelligence feed
        SYSTEM,      // hard-coded system rules (DNS interceptor etc.)
    }

    enum class Protocol { TCP, UDP, ANY }

    // ─────────────────────────────────────────────────────────────────────────
    // Matching
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if all non-null criteria match the given connection.
     *
     * @param uid           Android UID (-1 if unknown)
     * @param pkg           package name (null if unknown)
     * @param domain        hostname from SNI or DNS (null if unavailable)
     * @param ip            destination IP
     * @param port          destination port
     * @param protocol      IP protocol number (6=TCP, 17=UDP)
     * @param encStatus     encryption classification (UNKNOWN if not yet classified)
     */
    fun matches(
        uid:       Int,
        pkg:       String?,
        domain:    String?,
        ip:        String,
        port:      Int,
        protocol:  Int,
        encStatus: EncryptionStatus = EncryptionStatus.UNKNOWN,
    ): Boolean {
        if (!isEnabled) return false
        if (matchUid      != null && matchUid != uid)                          return false
        if (matchPackage  != null && matchPackage != pkg)                      return false
        if (matchDomain   != null && !domainMatches(domain ?: "", matchDomain)) return false
        if (matchIp       != null && !ipMatches(ip, matchIp))                  return false
        if (matchPort     != null && matchPort != port)                        return false
        if (matchProtocol != null && matchProtocol != Protocol.ANY) {
            val ruleProt = if (matchProtocol == Protocol.TCP) 6 else 17
            if (ruleProt != protocol) return false
        }
        if (matchEncryption != null && matchEncryption != encStatus)           return false
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun domainMatches(candidate: String, pattern: String): Boolean {
        if (pattern == "*") return true
        val c = candidate.lowercase()
        val p = pattern.lowercase()
        return if (p.startsWith("*.")) {
            val suffix = p.substring(1)
            c == suffix.substring(1) || c.endsWith(suffix)
        } else {
            c == p
        }
    }

    private fun ipMatches(ip: String, cidr: String): Boolean {
        val candidateIp = ipToIntOrNull(ip) ?: return false
        if (!cidr.contains('/')) return candidateIp == (ipToIntOrNull(cidr) ?: return false)
        return try {
            val (addr, prefix) = cidr.split('/')
            val networkIp = ipToIntOrNull(addr) ?: return false
            val prefixLen = prefix.toInt()
            if (prefixLen !in 0..32) return false
            val mask = if (prefixLen == 0) 0 else (-1 shl (32 - prefixLen))
            (candidateIp and mask) == (networkIp and mask)
        } catch (_: Exception) { false }
    }

    private fun ipToIntOrNull(ip: String): Int? {
        val octets = ip.split('.')
        if (octets.size != 4) return null
        var acc = 0
        for (octet in octets) {
            val value = octet.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            acc = (acc shl 8) or value
        }
        return acc
    }

    override fun toString(): String {
        val criteria = listOfNotNull(
            matchUid?.let       { "uid=$it" },
            matchPackage?.let   { "pkg=$it" },
            matchDomain?.let    { "domain=$it" },
            matchIp?.let        { "ip=$it" },
            matchPort?.let      { "port=$it" },
            matchEncryption?.let{ "enc=$it" },
        )
        return "FilterRule[$action ${criteria.joinToString(",")} src=$source pri=$priority]"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Companion — Factories
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        const val DEFAULT_PRIORITY = 100
        const val HIGH_PRIORITY    = 10
        const val LOW_PRIORITY     = 1_000

        private var idCounter = 0L
        private fun nextId() = "rule_${++idCounter}"

        // ── Domain ───────────────────────────────────────────────────────────
        fun blockDomain(domain: String, label: String = "Block $domain",
                        source: Source = Source.BLOCKLIST, priority: Int = DEFAULT_PRIORITY) =
            FilterRule(nextId(), label, Action.DENY, source, priority, matchDomain = domain)

        fun allowDomain(domain: String, label: String = "Allow $domain",
                        source: Source = Source.USER, priority: Int = HIGH_PRIORITY) =
            FilterRule(nextId(), label, Action.ALLOW, source, priority, matchDomain = domain)

        // ── IP ───────────────────────────────────────────────────────────────
        fun blockIp(ip: String, label: String = "Block $ip", source: Source = Source.USER) =
            FilterRule(nextId(), label, Action.DENY, source, DEFAULT_PRIORITY, matchIp = ip)

        // ── App ──────────────────────────────────────────────────────────────
        fun blockApp(uid: Int, label: String = "Block uid=$uid", source: Source = Source.USER) =
            FilterRule(nextId(), label, Action.DENY, source, HIGH_PRIORITY, matchUid = uid)

        fun blockPackage(pkg: String, label: String = "Block $pkg", source: Source = Source.USER) =
            FilterRule(nextId(), label, Action.DENY, source, HIGH_PRIORITY, matchPackage = pkg)

        // ── ★ Encryption Enforcement (new) ────────────────────────────────
        /**
         * Blocks all cleartext (HTTP) connections from any app.
         * Forces apps to use HTTPS.
         */
        fun blockCleartext(label: String = "Block all HTTP (cleartext)") =
            FilterRule(
                id              = nextId(),
                label           = label,
                action          = Action.DENY,
                source          = Source.USER,
                priority        = HIGH_PRIORITY,
                matchEncryption = EncryptionStatus.CLEARTEXT,
            )

        /**
         * Blocks cleartext connections from a specific app.
         * Useful to force a specific app to use HTTPS only.
         */
        fun blockCleartextForApp(uid: Int, label: String = "Block HTTP for uid=$uid") =
            FilterRule(
                id              = nextId(),
                label           = label,
                action          = Action.DENY,
                source          = Source.USER,
                priority        = HIGH_PRIORITY,
                matchUid        = uid,
                matchEncryption = EncryptionStatus.CLEARTEXT,
            )

        /**
         * Blocks connections using weak/deprecated TLS versions (1.0 / 1.1).
         */
        fun blockWeakTls(label: String = "Block deprecated TLS versions") =
            FilterRule(
                id              = nextId(),
                label           = label,
                action          = Action.DENY,
                source          = Source.USER,
                priority        = HIGH_PRIORITY,
                matchEncryption = EncryptionStatus.WEAK_TLS,
            )
    }
}


