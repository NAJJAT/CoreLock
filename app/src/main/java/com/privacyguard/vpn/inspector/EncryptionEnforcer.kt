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

    fun inspect(ip: IpPacket, tcp: TcpPacket): Result {
        val port = tcp.destinationPort
        val data = tcp.data

        Log.d(TAG, "Inspecting connection to port $port, data size ${data.size}")

        // FIXED: Don't block, just classify
        return when {
            port == 80 || port == 8080 -> {
                Log.d(TAG, "Port $port detected as CLEARTEXT")
                Result(EncryptionStatus.CLEARTEXT, null, null)
            }
            port == 443 || port == 8443 -> {
                val result = inspectTls(data)
                Log.d(TAG, "Port $port detected as ${result.encryptionStatus}")
                result
            }
            data.size > 5 && isTlsClientHello(data) -> {
                val result = inspectTls(data)
                Log.d(TAG, "TLS ClientHello detected as ${result.encryptionStatus}")
                result
            }
            data.size > 0 && isPlaintextHttp(data) -> {
                Log.d(TAG, "Plaintext HTTP detected as CLEARTEXT")
                Result(EncryptionStatus.CLEARTEXT, null, null)
            }
            else -> {
                Log.d(TAG, "Unknown encryption status for port $port")
                Result(EncryptionStatus.UNKNOWN, null, null)
            }
        }
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
               prefix.startsWith("DELETE ") || prefix.startsWith("HTTP/")
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
                                return String(data, nameStart, nameLen, Charsets.US_ASCII)
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