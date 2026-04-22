package com.privacyguard.app.utils

import java.io.RandomAccessFile

object ProcNetParser {

    data class ConnectionInfo(
        val localIp: String,
        val localPort: Int,
        val remoteIp: String,
        val remotePort: Int,
        val uid: Int,
        val protocol: String
    )

    fun getActiveTcpConnections(): List<ConnectionInfo> = parseProcFile("/proc/net/tcp", "TCP")

    fun getActiveUdpConnections(): List<ConnectionInfo> = parseProcFile("/proc/net/udp", "UDP")

    private fun parseProcFile(path: String, protocol: String): List<ConnectionInfo> {
        val connections = mutableListOf<ConnectionInfo>()
        try {
            RandomAccessFile(path, "r").use { file ->
                file.readLine()
                while (file.filePointer < file.length()) {
                    val line = file.readLine() ?: break
                    parseConnectionLine(line, protocol)?.let(connections::add)
                }
            }
        } catch (_: Exception) {
        }
        return connections
    }

    private fun parseConnectionLine(line: String, protocol: String): ConnectionInfo? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 8) return null

        return try {
            val local = parts[1].split(":")
            val remote = parts[2].split(":")
            if (local.size != 2 || remote.size != 2) return null

            ConnectionInfo(
                localIp = hexToIpv4(local[0]),
                localPort = local[1].toInt(16),
                remoteIp = hexToIpv4(remote[0]),
                remotePort = remote[1].toInt(16),
                uid = parts[7].toInt(),
                protocol = protocol
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun hexToIpv4(hex: String): String {
        if (hex.length != 8) return "0.0.0.0"
        return try {
            val bytes = hex.chunked(2).map { it.toInt(16) }
            "${bytes[3]}.${bytes[2]}.${bytes[1]}.${bytes[0]}"
        } catch (_: Exception) {
            "0.0.0.0"
        }
    }
}
