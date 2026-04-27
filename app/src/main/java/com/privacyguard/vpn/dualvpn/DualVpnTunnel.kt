package com.privacyguard.vpn.dualvpn

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Establishes a SOCKS5-chained dual-hop tunnel.
 *
 * Traffic path: device → hop1 → hop2 → target
 *
 * hop1 knows who you are (your real IP) but not the target.
 * hop2 knows the target but not your real IP.
 */
object DualVpnTunnel {

    private const val TAG = "DualVpnTunnel"

    /**
     * Opens a socket tunneled through two SOCKS5 proxies.
     * The returned socket is ready for data I/O to [targetHost]:[targetPort].
     * Caller is responsible for closing.
     */
    @Throws(IOException::class)
    fun connect(
        config: DualVpnConfig,
        targetHost: String,
        targetPort: Int,
        timeoutMs: Int = 15_000,
    ): Socket {
        val socket = Socket()
        socket.soTimeout = timeoutMs
        try {
            socket.connect(InetSocketAddress(config.firstHopHost, config.firstHopPort), timeoutMs)
            val ins = socket.getInputStream()
            val outs = socket.getOutputStream()

            // Tunnel through hop1 to reach hop2
            socks5Handshake(ins, outs, config.firstHopUser, config.firstHopPassword)
            socks5Connect(ins, outs, config.secondHopHost, config.secondHopPort)

            // Now talking through hop1 to hop2; establish the real target tunnel
            socks5Handshake(ins, outs, config.secondHopUser, config.secondHopPassword)
            socks5Connect(ins, outs, targetHost, targetPort)

            Log.i(TAG, "Dual-hop ready: ${config.firstHopHost} → ${config.secondHopHost} → $targetHost:$targetPort")
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw IOException("Dual VPN tunnel failed: ${e.message}", e)
        }
    }

    private fun socks5Handshake(ins: InputStream, outs: OutputStream, user: String, password: String) {
        val useAuth = user.isNotBlank()
        if (useAuth) {
            outs.write(byteArrayOf(0x05, 2, 0x00, 0x02))
        } else {
            outs.write(byteArrayOf(0x05, 1, 0x00))
        }
        outs.flush()

        val ver = ins.read()
        val method = ins.read()
        check(ver == 5) { "SOCKS5 version mismatch (got $ver)" }

        if (method == 0x02 && useAuth) {
            val authPkt = buildList<Byte> {
                add(0x01)
                add(user.length.toByte())
                addAll(user.toByteArray().toList())
                add(password.length.toByte())
                addAll(password.toByteArray().toList())
            }.toByteArray()
            outs.write(authPkt); outs.flush()
            ins.read() // auth version
            val status = ins.read()
            check(status == 0) { "SOCKS5 auth rejected (status $status)" }
        } else if (method != 0x00) {
            error("SOCKS5 no acceptable auth method (server chose $method)")
        }
    }

    private fun socks5Connect(ins: InputStream, outs: OutputStream, host: String, port: Int) {
        val hostBytes = host.toByteArray(Charsets.UTF_8)
        val pkt = buildList<Byte> {
            add(0x05); add(0x01); add(0x00) // VER CMD RSV
            add(0x03)                         // ATYP = domain
            add(hostBytes.size.toByte())
            addAll(hostBytes.toList())
            add((port ushr 8).toByte())
            add((port and 0xFF).toByte())
        }.toByteArray()
        outs.write(pkt); outs.flush()

        val reply = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = ins.read(reply, read, 4 - read)
            check(n >= 0) { "SOCKS5 stream closed before reply" }
            read += n
        }
        check(reply[1].toInt() == 0) { "SOCKS5 CONNECT rejected: code ${reply[1].toInt()}" }

        // Drain address/port from reply
        when (reply[3].toInt()) {
            0x01 -> ins.readNBytes(4 + 2)   // IPv4 + port
            0x03 -> { val len = ins.read(); ins.readNBytes(len + 2) } // domain + port
            0x04 -> ins.readNBytes(16 + 2)  // IPv6 + port
        }
    }

    /**
     * Negotiates the two-hop SOCKS5 chain on an already-connected blocking stream.
     * Use this when the caller has a [java.nio.channels.SocketChannel] open in blocking mode
     * to [config.firstHopHost] and wants to punch through to [targetHost]:[targetPort].
     *
     * Sequence:
     *   auth with hop1 → connect hop1 to hop2 → auth with hop2 → connect hop2 to target
     */
    @Throws(java.io.IOException::class)
    fun negotiateChain(
        ins: InputStream,
        outs: OutputStream,
        config: DualVpnConfig,
        targetHost: String,
        targetPort: Int,
    ) {
        socks5Handshake(ins, outs, config.firstHopUser, config.firstHopPassword)
        socks5Connect(ins, outs, config.secondHopHost, config.secondHopPort)
        socks5Handshake(ins, outs, config.secondHopUser, config.secondHopPassword)
        socks5Connect(ins, outs, targetHost, targetPort)
    }

    private fun InputStream.readNBytes(n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = read(buf, read, n - read)
            if (r < 0) break
            read += r
        }
        return buf
    }
}
