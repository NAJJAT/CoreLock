/**
 * SessionKey.kt
 *
 * Uniquely identifies a network connection using the standard 5-tuple
 *
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.core.session

data class SessionKey(
    val srcIp: Int,
    val srcPort: Int,
    val dstIp: Int,
    val dstPort: Int,
    val protocol: Int
) {
    fun isTcp(): Boolean = protocol == 6
    fun isUdp(): Boolean = protocol == 17

    fun reverse(): SessionKey {
        return SessionKey(dstIp, dstPort, srcIp, srcPort, protocol)
    }

    override fun toString(): String {
        return "${ipToString(srcIp)}:$srcPort → ${ipToString(dstIp)}:$dstPort (${if (isTcp()) "TCP" else "UDP"})"
    }

    private fun ipToString(ip: Int): String {
        return "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"
    }
}