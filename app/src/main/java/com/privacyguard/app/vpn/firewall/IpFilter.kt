/**
 * IpFilter.kt
 * 
 * IP address filtering for PrivacyGuard
 * 
 * What it does:
 * =============
 * Checks IP addresses against blocklists and user rules.
 * This is the SECONDARY blocking mechanism (after DNS).
 * 
 * Why IP blocking is needed:
 * ==========================
 * - Some apps use hardcoded IP addresses (no DNS lookup)
 * - Some malware uses IP addresses directly
 * - DNS blocking can be bypassed with DNS-over-HTTPS
 * - IP blocking provides defense in depth
 * 
 * Matching Methods:
 * =================
 * - Exact IP: "192.168.1.1" matches exactly
 * - CIDR: "192.168.1.0/24" matches range
 * - IP range: "192.168.1.1-192.168.1.254" (future)
 * 
 * Performance Requirements:
 * =========================
 * - Lookup must be O(1) or O(log n)
 * - Called for every packet (1000+ times per second)
 * - CIDR matching is slower, used only when necessary
 * 
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.vpn.firewall

import com.privacyguard.app.core.filter.BlocklistCategory
import com.privacyguard.app.core.filter.BlocklistEntry
import com.privacyguard.app.core.filter.BlocklistSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

// ============================================================
// IP Filter Result
// ============================================================

/**
 * Result of IP filter check
 */
data class IpFilterResult(
    val isBlocked: Boolean,
    val reason: String,
    val matchedRule: String? = null,
    val matchedBlocklist: BlocklistEntry? = null
) {
    companion object {
        fun allowed(): IpFilterResult = IpFilterResult(false, "IP not in blocklist")
        fun blockedByRule(rule: String): IpFilterResult = IpFilterResult(true, "Blocked by user rule: $rule", rule)
        fun blockedByBlocklist(entry: BlocklistEntry): IpFilterResult = 
            IpFilterResult(true, "Blocked by ${entry.source}: ${entry.domain}", null, entry)
        fun blockedByCidr(cidr: String): IpFilterResult = 
            IpFilterResult(true, "Blocked by CIDR rule: $cidr", cidr)
    }
}

// ============================================================
// CIDR Utils
// ============================================================

/**
 * Represents a CIDR range (e.g., 192.168.1.0/24)
 */
data class CidrRange(
    val network: Long,    // Network address as Long
    val maskBits: Int,    // Number of bits in mask (0-32)
    val entry: BlocklistEntry? = null,
    val userRule: String? = null
) {
    /**
     * Checks if an IP is within this CIDR range
     */
    fun contains(ip: Long): Boolean {
        val mask = if (maskBits == 0) 0 else -1L shl (32 - maskBits)
        return (network and mask) == (ip and mask)
    }
    
    companion object {
        /**
         * Parses a CIDR string (e.g., "192.168.1.0/24")
         */
        fun parse(cidr: String): CidrRange? {
            try {
                val parts = cidr.split('/')
                if (parts.size != 2) return null
                
                val ip = ipToLong(parts[0]) ?: return null
                val maskBits = parts[1].toIntOrNull() ?: return null
                
                if (maskBits !in 0..32) return null
                
                return CidrRange(ip, maskBits)
            } catch (e: Exception) {
                return null
            }
        }
        
        /**
         * Converts IP string to Long
         */
        private fun ipToLong(ip: String): Long? {
            val parts = ip.split('.')
            if (parts.size != 4) return null
            
            try {
                var result = 0L
                for (i in 0..3) {
                    val part = parts[i].toLong()
                    if (part !in 0..255) return null
                    result = (result shl 8) or part
                }
                return result
            } catch (e: NumberFormatException) {
                return null
            }
        }
    }
}

// ============================================================
// IpFilter - Main Implementation
// ============================================================

/**
 * IP filter - checks IP addresses against blocklists
 */
class IpFilter {
    
    companion object {
        private const val TAG = "IpFilter"
        
        // Private IP ranges (never blocked by default)
        private val PRIVATE_RANGES = listOf(
            CidrRange.parse("10.0.0.0/8")!!,      // Class A private
            CidrRange.parse("172.16.0.0/12")!!,    // Class B private
            CidrRange.parse("192.168.0.0/16")!!,   // Class C private
            CidrRange.parse("127.0.0.0/8")!!,      // Loopback
            CidrRange.parse("169.254.0.0/16")!!,   // Link-local
            CidrRange.parse("224.0.0.0/4")!!,      // Multicast
            CidrRange.parse("240.0.0.0/4")!!       // Reserved
        )
    }
    
    // ============================================================
    // Blocklist Storage
    // ============================================================
    
    // Exact IP blocklist (O(1) lookup)
    private val exactBlocklist = ConcurrentHashMap<Long, BlocklistEntry>()
    
    // CIDR blocklist (iterated, slower)
    private val cidrBlocklist = CopyOnWriteArrayList<CidrRange>()
    
    // User exact allow/block
    private val userExactAllow = ConcurrentHashMap<Long, String>()
    private val userExactBlock = ConcurrentHashMap<Long, String>()
    
    // User CIDR rules
    private val userCidrAllow = CopyOnWriteArrayList<CidrRange>()
    private val userCidrBlock = CopyOnWriteArrayList<CidrRange>()
    
    // Whether to block private IPs (default: false, for safety)
    private var blockPrivateIps = false
    
    // Statistics
    private val totalLookups = AtomicLong(0)
    private val exactHits = AtomicLong(0)
    private val cidrHits = AtomicLong(0)
    private val privateIpHits = AtomicLong(0)
    private val allowHits = AtomicLong(0)
    
    // ============================================================
    // Blocklist Management
    // ============================================================
    
    /**
     * Updates the IP blocklist with new entries
     */
    fun updateBlocklist(entries: List<BlocklistEntry>) {
        exactBlocklist.clear()
        cidrBlocklist.clear()
        
        for (entry in entries) {
            val ip = entry.domain
            if (ip.contains('/')) {
                // CIDR notation
                CidrRange.parse(ip)?.let { cidr ->
                    cidrBlocklist.add(CidrRange(cidr.network, cidr.maskBits, entry))
                }
            } else {
                // Exact IP
                ipToLong(ip)?.let { ipLong ->
                    exactBlocklist[ipLong] = entry
                }
            }
        }
        
        android.util.Log.d(TAG, "IP blocklist updated: ${exactBlocklist.size} exact, ${cidrBlocklist.size} CIDR")
    }
    
    /**
     * Adds a user block rule for an IP or CIDR
     */
    fun addUserBlockRule(ipOrCidr: String, description: String = "User blocked") {
        if (ipOrCidr.contains('/')) {
            CidrRange.parse(ipOrCidr)?.let { cidr ->
                userCidrBlock.add(CidrRange(cidr.network, cidr.maskBits, userRule = description))
            }
        } else {
            ipToLong(ipOrCidr)?.let { ip ->
                userExactBlock[ip] = description
            }
        }
    }
    
    /**
     * Adds a user allow rule for an IP or CIDR
     */
    fun addUserAllowRule(ipOrCidr: String, description: String = "User allowed") {
        if (ipOrCidr.contains('/')) {
            CidrRange.parse(ipOrCidr)?.let { cidr ->
                userCidrAllow.add(CidrRange(cidr.network, cidr.maskBits, userRule = description))
            }
        } else {
            ipToLong(ipOrCidr)?.let { ip ->
                userExactAllow[ip] = description
            }
        }
    }
    
    /**
     * Removes a user rule
     */
    fun removeUserRule(ipOrCidr: String) {
        if (ipOrCidr.contains('/')) {
            userCidrBlock.removeAll { it.toString() == ipOrCidr }
            userCidrAllow.removeAll { it.toString() == ipOrCidr }
        } else {
            ipToLong(ipOrCidr)?.let { ip ->
                userExactBlock.remove(ip)
                userExactAllow.remove(ip)
            }
        }
    }
    
    /**
     * Sets whether to block private IPs (default: false)
     */
    fun setBlockPrivateIps(block: Boolean) {
        blockPrivateIps = block
    }
    
    // ============================================================
    // IP Checking
    // ============================================================
    
    /**
     * Checks if an IP address should be blocked
     * 
     * This is the MAIN entry point for IP blocking decisions.
     * 
     * Order of checks (first match wins):
     * 1. User allow list (explicit allow)
     * 2. User block list (explicit block)
     * 3. Private IP check (if blocking enabled)
     * 4. Exact blocklist match
     * 5. CIDR blocklist match
     * 6. User CIDR block rules
     * 7. Not blocked (allowed)
     * 
     * @param ip IP address as string (dotted decimal)
     * @return IpFilterResult with decision and reason
     */
    fun checkIp(ip: String): IpFilterResult {
        val ipLong = ipToLong(ip) ?: return IpFilterResult.allowed()
        return checkIp(ipLong)
    }
    
    /**
     * Checks if an IP address (as Long) should be blocked
     */
    fun checkIp(ip: Long): IpFilterResult {
        totalLookups.incrementAndGet()
        
        // Level 1: User allow list (explicit allow, highest priority)
        if (userExactAllow.containsKey(ip)) {
            allowHits.incrementAndGet()
            return IpFilterResult.allowed()
        }
        
        // Level 2: User block list (explicit block)
        userExactBlock[ip]?.let { description ->
            exactHits.incrementAndGet()
            return IpFilterResult.blockedByRule(description)
        }
        
        // Level 3: Private IP check
        if (!blockPrivateIps && isPrivateIp(ip)) {
            // Private IPs are allowed by default (for local network functionality)
            return IpFilterResult.allowed()
        }
        
        // Level 4: Exact blocklist match
        exactBlocklist[ip]?.let { entry ->
            exactHits.incrementAndGet()
            return IpFilterResult.blockedByBlocklist(entry)
        }
        
        // Level 5: CIDR blocklist match
        for (cidr in cidrBlocklist) {
            if (cidr.contains(ip)) {
                cidrHits.incrementAndGet()
                return IpFilterResult.blockedByBlocklist(cidr.entry!!)
            }
        }
        
        // Level 6: User CIDR block rules
        for (cidr in userCidrBlock) {
            if (cidr.contains(ip)) {
                cidrHits.incrementAndGet()
                return IpFilterResult.blockedByCidr(cidr.toString())
            }
        }
        
        // Level 7: Not blocked
        return IpFilterResult.allowed()
    }
    
    /**
     * Checks if an IP is private (RFC 1918)
     */
    fun isPrivateIp(ip: Long): Boolean {
        for (range in PRIVATE_RANGES) {
            if (range.contains(ip)) return true
        }
        return false
    }
    
    /**
     * Checks if an IP is private (string version)
     */
    fun isPrivateIp(ip: String): Boolean {
        val ipLong = ipToLong(ip) ?: return false
        return isPrivateIp(ipLong)
    }
    
    // ============================================================
    // Utility Functions
    // ============================================================
    
    /**
     * Converts IP string to Long
     */
    private fun ipToLong(ip: String): Long? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        
        try {
            var result = 0L
            for (i in 0..3) {
                val part = parts[i].toLong()
                if (part !in 0..255) return null
                result = (result shl 8) or part
            }
            return result
        } catch (e: NumberFormatException) {
            return null
        }
    }
    
    /**
     * Converts Long to IP string
     */
    fun longToIp(ip: Long): String {
        return "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"
    }
    
    // ============================================================
    // Statistics
    // ============================================================
    
    /**
     * Returns filter statistics
     */
    fun getStats(): IpFilterStats {
        return IpFilterStats(
            exactBlocklistSize = exactBlocklist.size,
            cidrBlocklistSize = cidrBlocklist.size,
            userExactRules = userExactBlock.size + userExactAllow.size,
            userCidrRules = userCidrBlock.size + userCidrAllow.size,
            totalLookups = totalLookups.get(),
            exactHits = exactHits.get(),
            cidrHits = cidrHits.get(),
            privateIpHits = privateIpHits.get(),
            allowHits = allowHits.get(),
            blockRate = if (totalLookups.get() > 0) {
                (exactHits.get() + cidrHits.get()).toDouble() / totalLookups.get()
            } else 0.0
        )
    }
    
    /**
     * Resets statistics
     */
    fun resetStats() {
        totalLookups.set(0)
        exactHits.set(0)
        cidrHits.set(0)
        privateIpHits.set(0)
        allowHits.set(0)
    }
}

/**
 * IP filter statistics
 */
data class IpFilterStats(
    val exactBlocklistSize: Int,
    val cidrBlocklistSize: Int,
    val userExactRules: Int,
    val userCidrRules: Int,
    val totalLookups: Long,
    val exactHits: Long,
    val cidrHits: Long,
    val privateIpHits: Long,
    val allowHits: Long,
    val blockRate: Double
)