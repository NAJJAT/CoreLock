package com.privacyguard.app.vpn

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps TCP/UDP source ports to Android UIDs by reading /proc/net/tcp(6) and /proc/net/udp(6).
 *
 * Called only on the first packet of each connection (SYN for TCP, first UDP datagram)
 * so the per-packet overhead is limited to a cache lookup.
 *
 * Cache is keyed by (protocol, srcPort) and evicted when the session closes.
 */
object UidMapper {

    private data class Key(val protocol: Int, val srcPort: Int)
    private val cache = ConcurrentHashMap<Key, Int>(64)

    fun uidForSrcPort(srcPort: Int, protocol: Int): Int {
        val key = Key(protocol, srcPort)
        cache[key]?.let { return it }
        val uid = lookupProc(srcPort, protocol)
        if (uid >= 0) cache[key] = uid
        return uid
    }

    fun evict(srcPort: Int, protocol: Int) { cache.remove(Key(protocol, srcPort)) }

    fun clear() { cache.clear() }

    // ─── /proc/net parsing ────────────────────────────────────────────────────

    private fun lookupProc(srcPort: Int, protocol: Int): Int {
        val files = if (protocol == 6) {
            arrayOf("/proc/net/tcp", "/proc/net/tcp6")
        } else {
            arrayOf("/proc/net/udp", "/proc/net/udp6")
        }
        for (path in files) {
            val uid = scanProcFile(File(path), srcPort)
            if (uid >= 0) return uid
        }
        return -1
    }

    private fun scanProcFile(file: File, srcPort: Int): Int {
        if (!file.exists() || !file.canRead()) return -1
        val portHex = "%04X".format(srcPort)
        try {
            file.bufferedReader().use { reader ->
                reader.readLine() // skip header
                var line = reader.readLine()
                while (line != null) {
                    val parts = line.trim().split("\\s+".toRegex())
                    // Column layout: sl local_address rem_address st tx_queue:rx_queue tr tm->when retrnsmt uid timeout inode ...
                    // index:          0  1              2           3  4                 5  6        7   8       9
                    if (parts.size < 8) { line = reader.readLine(); continue }
                    val local = parts[1]
                    val colonIdx = local.lastIndexOf(':')
                    if (colonIdx >= 0 && local.substring(colonIdx + 1).equals(portHex, ignoreCase = true)) {
                        return parts.getOrNull(7)?.toIntOrNull() ?: -1
                    }
                    line = reader.readLine()
                }
            }
        } catch (_: Exception) { /* permission denied, or parse error */ }
        return -1
    }
}
