package com.privacyguard.vpn.inspector

import android.util.Log
import com.privacyguard.core.metadata.EncryptionStatus
import com.privacyguard.core.metadata.TlsVersion
import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.packet.TcpPacket

class EncryptionEnforcer {

    companion object {
        private const val TAG = "EncryptionEnforcer"
    }

    data class Result(
        val encryptionStatus: EncryptionStatus,
        val tlsVersion: TlsVersion?,
        val sniHostname: String?,
    )

    fun inspect(ip: IpPacket, tcp: TcpPacket): Result = inspect(tcp.destinationPort, tcp.data)

    /**
     * Classifies the first client bytes of a TCP connection. Pass the whole
     * reassembled first TLS record: a ClientHello split over several segments has
     * its version and supported_versions extension past the first one.
     *
     * Decided by what the bytes are, never by the port: port 80 also carries
     * encrypted protocols (WhatsApp, Facebook chat), and anything can run on 443.
     * Only an HTTP request line is cleartext; anything we cannot recognise is UNKNOWN.
     */
    @Suppress("UNUSED_PARAMETER")
    fun inspect(port: Int, data: ByteArray): Result = when {
        isTlsClientHello(data) -> inspectTls(data)
        isPlaintextHttp(data) -> Result(EncryptionStatus.CLEARTEXT, null, null)
        else -> Result(EncryptionStatus.UNKNOWN, null, null)
    }

    /**
     * Classifies the first datagram of a UDP flow: a QUIC long-header packet with a
     * known version is QUIC (always encrypted, TLS 1.3 inside); anything else is UNKNOWN.
     */
    fun inspectUdp(data: ByteArray): EncryptionStatus =
        if (isQuicLongHeader(data)) EncryptionStatus.QUIC else EncryptionStatus.UNKNOWN

    private fun isQuicLongHeader(data: ByteArray): Boolean {
        if (data.size < 7) return false
        val first = data[0].toInt() and 0xFF
        // Long header (bit 7) with the fixed bit (bit 6) set.
        if ((first and 0xC0) != 0xC0) return false
        val version = ((data[1].toLong() and 0xFF) shl 24) or ((data[2].toLong() and 0xFF) shl 16) or
            ((data[3].toLong() and 0xFF) shl 8) or (data[4].toLong() and 0xFF)
        return version == 0x00000001L ||                 // QUIC v1 (RFC 9000)
            version == 0x6b3343cfL ||                    // QUIC v2 (RFC 9369)
            (version and 0xFFFFFF00L) == 0xFF000000L ||  // IETF drafts
            (version and 0xFF000000L) == 0x51000000L      // Google QUIC ("Q0xx")
    }

    private fun inspectTls(data: ByteArray): Result {
        if (data.size < 6) return Result(EncryptionStatus.TLS, null, null)
        if (!isTlsClientHello(data)) return Result(EncryptionStatus.TLS, null, null)

        val major = data[1].toInt() and 0xFF
        val minor = data[2].toInt() and 0xFF

        val recordVersion = when {
            major == 3 && minor == 4 -> TlsVersion.TLS_1_3
            major == 3 && minor == 3 -> TlsVersion.TLS_1_2
            major == 3 && minor == 2 -> TlsVersion.TLS_1_1
            major == 3 && minor == 1 -> TlsVersion.TLS_1_0
            else -> TlsVersion.UNKNOWN
        }

        val sni = extractSni(data)
        val hasTls13 = hasTls13SupportedVersions(data)
        val finalVersion = if (hasTls13) TlsVersion.TLS_1_3 else recordVersion

        val encStatus = when (finalVersion) {
            TlsVersion.TLS_1_3 -> EncryptionStatus.TLS_1_3
            TlsVersion.TLS_1_2 -> EncryptionStatus.TLS_1_2
            TlsVersion.TLS_1_0, TlsVersion.TLS_1_1 -> EncryptionStatus.WEAK_TLS
            else -> EncryptionStatus.TLS
        }

        return Result(encStatus, finalVersion, sni)
    }

    private fun isTlsClientHello(data: ByteArray): Boolean {
        return data.size >= 6 && 
               data[0].toInt() == 0x16 &&   // Content type: handshake
               data[5].toInt() == 0x01       // Handshake type: ClientHello
    }

    private fun isPlaintextHttp(data: ByteArray): Boolean {
        if (data.size < 4) return false
        val prefix = String(data, 0, minOf(8, data.size), Charsets.ISO_8859_1)
        return prefix.startsWith("GET ") || prefix.startsWith("POST ") ||
               prefix.startsWith("HEAD ") || prefix.startsWith("PUT ") ||
               prefix.startsWith("DELETE ") || prefix.startsWith("OPTIONS ") ||
               prefix.startsWith("PATCH ") || prefix.startsWith("CONNECT ") || prefix.startsWith("HTTP/")
    }

    private fun extractSni(data: ByteArray): String? {
        try {
            var offset = 5 + 4 + 2 + 32  // record, handshake, version, random
            if (offset >= data.size) return null

            val sessionIdLen = data[offset].toInt() and 0xFF
            offset += 1 + sessionIdLen
            if (offset + 2 >= data.size) return null

            val cipherSuitesLen = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            offset += 2 + cipherSuitesLen
            if (offset >= data.size) return null

            val compressionLen = data[offset].toInt() and 0xFF
            offset += 1 + compressionLen
            if (offset + 2 >= data.size) return null

            val extensionsLen = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            offset += 2

            val extEnd = offset + extensionsLen
            while (offset + 4 <= extEnd && offset + 4 <= data.size) {
                val extType = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
                val extLen = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
                offset += 4
                
                if (extType == 0) { // SNI extension
                    if (offset + 5 <= data.size) {
                        val nameType = data[offset + 2].toInt() and 0xFF
                        if (nameType == 0) {
                            val nameLen = ((data[offset + 3].toInt() and 0xFF) shl 8) or (data[offset + 4].toInt() and 0xFF)
                            val nameStart = offset + 5
                            if (nameStart + nameLen <= data.size) {
                                return com.privacyguard.core.utils.Hostnames.validSni(data, nameStart, nameLen)
                            }
                        }
                    }
                }
                offset += extLen
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract SNI: ${e.message}")
        }
        return null
    }

    private fun hasTls13SupportedVersions(data: ByteArray): Boolean {
        try {
            var offset = 5 + 4 + 2 + 32
            if (offset >= data.size) return false
            
            val sessionIdLen = data[offset].toInt() and 0xFF
            offset += 1 + sessionIdLen
            if (offset + 2 >= data.size) return false
            
            val cipherLen = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            offset += 2 + cipherLen
            if (offset >= data.size) return false
            
            val compLen = data[offset].toInt() and 0xFF
            offset += 1 + compLen
            if (offset + 2 >= data.size) return false
            
            val extLen = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            offset += 2
            
            val extEnd = offset + extLen
            while (offset + 4 <= extEnd && offset + 4 <= data.size) {
                val extType = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
                val extDataLen = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
                offset += 4
                
                if (extType == 0x002B) { // supported_versions
                    val end = offset + extDataLen
                    var i = offset + 1
                    while (i + 2 <= end && i + 2 <= data.size) {
                        val v = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                        if (v == 0x0304) return true
                        i += 2
                    }
                }
                offset += extDataLen
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check TLS 1.3: ${e.message}")
        }
        return false
    }
}