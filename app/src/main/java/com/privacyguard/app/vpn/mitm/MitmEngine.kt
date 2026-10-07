package com.privacyguard.vpn.mitm

import android.util.Log
import com.privacyguard.core.session.Session
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.channels.SocketChannel
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*
import com.privacyguard.vpn.mitm.PinningDetector

// ADDED: runtime status surfaced to the UI so empty payload lists can explain why.
data class MitmRuntimeStatus(
    val state: String = "IDLE",
    val message: String = "MITM idle",
    val domain: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * MITM Engine Core - Performs TLS interception
 *
 * Terminates TLS connections from the device and creates new TLS connections
 * to the real server, reading plaintext in the middle.
 *
 * Business Reason: Provides full payload visibility for enterprise security teams
 * to inspect encrypted HTTPS traffic on managed devices.
 *
 * Thread Safety: Each interception runs in its own coroutine scope, isolated
 * from other sessions.
 */
class MitmEngine(
    private val certForger: CertForger,
    private val pinningDetector: PinningDetector,
    private val caManager: CaManager,
    private val payloadParser: PayloadParser,
    private val protectSocket: (java.net.Socket) -> Boolean = { true },
) {

    companion object {
        private const val TAG = "MitmEngine"
        private const val BUFFER_SIZE = 32768
        // ADDED
        private val _statusFlow = MutableStateFlow(MitmRuntimeStatus())
        // ADDED
        val statusFlow: StateFlow<MitmRuntimeStatus> = _statusFlow.asStateFlow()

        /** Only TcpForwarder's own loopback socket, identified by its bound source port. */
        internal fun isExpectedPeer(address: InetAddress?, port: Int, expectedClientPort: Int): Boolean =
            address != null && address.isLoopbackAddress && expectedClientPort > 0 && port == expectedClientPort
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Intercept TLS traffic for a session.
     *
     * Creates a local SSL server socket synchronously (so the port is known before returning),
     * then launches a coroutine to accept the device connection and proxy it to the real server.
     * TcpForwarder must redirect the session's NIO channel to this port after calling intercept().
     *
     * The server socket listens on loopback only and accepts a single peer: the
     * loopback socket bound to [expectedClientPort] by TcpForwarder. Any other
     * local app that finds the port is dropped without consuming the slot.
     *
     * @param session The VPN session (already classified as TLS)
     * @param expectedClientPort source port of TcpForwarder's loopback socket
     * @param onPayload Callback for each payload chunk (direction, bytes, session)
     * @return local port the MITM server is listening on, or -1 if interception was skipped/failed
     */
    fun intercept(
        session: Session,
        expectedClientPort: Int,
        onPayload: (direction: String, bytes: ByteArray, session: Session) -> Unit
    ): Int {
        val domain = session.tlsSni ?: return -1
        // The SNI is attacker-controlled input that ends up inside a certificate.
        if (!CertForger.isValidHostname(domain)) return -1

        // Guard: CA must be ready before we can forge leaf certs.
        // CaManager.initialize() runs async at VPN start; the first TLS connection
        // could theoretically arrive before it completes on slow devices.
        if (caManager.getCaCert() == null || caManager.getCaKey() == null) {
            Log.w(TAG, "intercept: CA not ready yet — skipping MITM for $domain")
            return -1
        }

        // Check if we should skip due to pinning
        if (pinningDetector.isPinned(session.ownerPackage, domain)) {
            Log.d(TAG, "Skipping MITM for pinned domain: $domain")
            _statusFlow.value = MitmRuntimeStatus(
                state = "PINNED_BYPASS",
                message = "Pinned app/domain bypassed interception",
                domain = domain,
                timestamp = System.currentTimeMillis(),
            )
            return -1
        }

        // Create server socket NOW (synchronously) so the caller gets the port immediately
        val serverSocket = try {
            val ctx = createServerSslContext(domain)
            (ctx.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket).also {
                it.soTimeout = 10_000  // 10 s for TcpForwarder to connect back to us
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MITM server socket for $domain", e)
            return -1
        }
        val localPort = serverSocket.localPort
        Log.d(TAG, "MITM server socket on port $localPort for $domain")

        session.isMitmIntercepted = true

        // Launch coroutine to wait for device connection and proxy the traffic
        coroutineScope.launch {
            try {
                _statusFlow.value = MitmRuntimeStatus(
                    state = "STARTING",
                    message = "Waiting for device on port $localPort",
                    domain = domain,
                    timestamp = System.currentTimeMillis(),
                )
                performInterception(session, domain, serverSocket, expectedClientPort, onPayload)
            } catch (e: MitmHandshakeException) {
                handleHandshakeFailure(session, domain, e)
                serverSocket.runCatching { close() }
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "MITM device connection timed out for $domain")
                _statusFlow.value = MitmRuntimeStatus(
                    state = "TIMEOUT",
                    message = "Timed out waiting for the app to connect to the local MITM socket",
                    domain = domain,
                    timestamp = System.currentTimeMillis(),
                )
                session.isMitmIntercepted = false
                serverSocket.runCatching { close() }
            } catch (e: Exception) {
                Log.e(TAG, "MITM interception failed for ${session.key}", e)
                _statusFlow.value = MitmRuntimeStatus(
                    state = "ERROR",
                    message = e.message ?: e::class.java.simpleName,
                    domain = domain,
                    timestamp = System.currentTimeMillis(),
                )
                session.isMitmIntercepted = false
                serverSocket.runCatching { close() }
            }
        }

        return localPort
    }

    private suspend fun performInterception(
        session: Session,
        domain: String,
        serverSocket: SSLServerSocket,
        expectedClientPort: Int,
        onPayload: (String, ByteArray, Session) -> Unit
    ) = withContext(Dispatchers.IO) {

        serverSocket.use {
            // 1. Accept the device connection FIRST — TcpForwarder connects to this port
            //    immediately after intercept() returns, so this returns within milliseconds.
            //    Doing the real-server handshake first would block here for 200–500ms and
            //    could race with the device's connection attempt timing out.
            val deviceSocket = acceptExpectedPeer(it, expectedClientPort)

            // 2. Now connect to the real server.
            // VpnService.protect() only works on a plain socket that already owns a
            // file descriptor — protecting an SSLSocket silently fails, which sends
            // our upstream traffic back through the tunnel and hangs the handshake.
            // So protect a plain socket, connect it, THEN layer TLS over it.
            val clientSslContext = createClientSslContext()
            // SocketChannel.open() allocates the native fd eagerly, so protect()
            // can exclude it from the tunnel. A bare Socket() has no fd until it
            // connects, and protecting it there silently fails (the bug that made
            // every upstream handshake loop back through the tunnel and hang).
            val plainSocket = SocketChannel.open().socket()
            // Close both ends on any failure below; otherwise the app's connection
            // hangs until its own timeout instead of failing fast.
            try {
                if (!protectSocket(plainSocket)) {
                    Log.w(TAG, "protect() failed for upstream socket to $domain — traffic may loop through the tunnel")
                }
                // Connect to the IP the app actually dialled rather than resolving the
                // SNI again — avoids a DNS round-trip through our own tunnel and keeps
                // the app on the same server it chose.
                val upstream = InetSocketAddress(
                    InetAddress.getByName(session.key.destinationIp), session.key.destinationPort
                )
                plainSocket.connect(upstream, 15_000)

                // Wrap TLS over the protected, already-connected socket. Passing the
                // SNI hostname makes SNI and HTTPS hostname verification use the name,
                // not the bare IP.
                val clientSocket = clientSslContext.socketFactory
                    .createSocket(plainSocket, domain, session.key.destinationPort, true) as SSLSocket
                val params = clientSocket.sslParameters
                params.serverNames = listOf(SNIHostName(domain))
                params.endpointIdentificationAlgorithm = "HTTPS"
                clientSocket.sslParameters = params

                try {
                    clientSocket.startHandshake()   // validates real server cert normally
                } catch (e: SSLException) {
                    throw MitmHandshakeException(deviceSide = false, cause = e)
                }

                // 3. Explicitly complete TLS handshake with the device now that we know the
                //    real server is up. Device sent its ClientHello; we present the forged cert.
                try {
                    deviceSocket.startHandshake()
                } catch (e: SSLException) {
                    throw MitmHandshakeException(deviceSide = true, cause = e)
                }

                _statusFlow.value = MitmRuntimeStatus(
                    state = "ACTIVE",
                    message = "Interception active",
                    domain = domain,
                    timestamp = System.currentTimeMillis(),
                )

                // Relay data both ways with interception
                val deviceToServer = async {
                    relayWithInterception(
                        deviceSocket.inputStream,
                        clientSocket.outputStream,
                        "OUTBOUND",
                        session,
                        onPayload
                    )
                }

                val serverToDevice = async {
                    relayWithInterception(
                        clientSocket.inputStream,
                        deviceSocket.outputStream,
                        "INBOUND",
                        session,
                        onPayload
                    )
                }

                // Wait for either direction to complete
                awaitAll(deviceToServer, serverToDevice)
                _statusFlow.value = MitmRuntimeStatus(
                    state = "IDLE",
                    message = "MITM idle",
                    domain = domain,
                    timestamp = System.currentTimeMillis(),
                )
            } finally {
                plainSocket.runCatching { close() }
                deviceSocket.runCatching { close() }
            }
        }
    }

    /**
     * Accepts until the expected TcpForwarder socket connects; anything else is
     * closed before the TLS handshake. The socket's soTimeout bounds each wait,
     * and the overall deadline stops a flood of strangers from holding it open.
     */
    private fun acceptExpectedPeer(serverSocket: SSLServerSocket, expectedClientPort: Int): SSLSocket {
        val deadline = System.currentTimeMillis() + serverSocket.soTimeout
        while (true) {
            val candidate = serverSocket.accept() as SSLSocket
            if (isExpectedPeer(candidate.inetAddress, candidate.port, expectedClientPort)) return candidate
            Log.w(TAG, "MITM: rejected unexpected local connection")
            candidate.runCatching { close() }
            if (System.currentTimeMillis() >= deadline) throw SocketTimeoutException("expected peer never connected")
        }
    }

    private fun relayWithInterception(
        from: InputStream,
        to: OutputStream,
        direction: String,
        session: Session,
        onPayload: (String, ByteArray, Session) -> Unit
    ) {
        val buffer = ByteArray(BUFFER_SIZE)

        try {
            while (true) {
                val bytesRead = from.read(buffer)
                if (bytesRead <= 0) break

                var payload = buffer.copyOf(bytesRead)
                // Ask servers for gzip/deflate instead of br/zstd so captured
                // response bodies can be decoded and shown.
                if (direction == "OUTBOUND") payload = HttpCodec.rewriteAcceptEncoding(payload)

                // Call payload callback for inspection
                onPayload(direction, payload, session)

                // Forward to destination
                to.write(payload, 0, payload.size)
                to.flush()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Relay interrupted: ${e.message}")
        } finally {
            try {
                from.close()
                to.close()
            } catch (e: Exception) { }
        }
    }

    private fun createServerSslContext(domain: String): SSLContext {
        val (cert, privateKey) = certForger.forge(domain)

        // FIXED: Proper X509KeyManager implementation with all required methods
        val keyManagers = arrayOf(object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? {
                return null
            }

            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? {
                return arrayOf("server")
            }

            override fun chooseClientAlias(
                keyType: Array<out String>?,
                issuers: Array<out Principal>?,
                socket: Socket?
            ): String? {
                return null
            }

            override fun chooseServerAlias(
                keyType: String?,
                issuers: Array<out Principal>?,
                socket: Socket?
            ): String? {
                return "server"
            }

            override fun getCertificateChain(alias: String?): Array<X509Certificate> {
                // Include the CA cert so Android can build the full trust chain:
                // leaf → CA. Without the CA here, Android's TLS path builder fails
                // even when the CA is installed in the device trust store.
                val caCert = caManager.getCaCert()
                return if (caCert != null) arrayOf(cert, caCert) else arrayOf(cert)
            }

            override fun getPrivateKey(alias: String?): PrivateKey? {
                return privateKey
            }
        })

        // TrustManager that trusts all client certificates (we accept device's connections)
        val trustManagers = arrayOf(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) { }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagers, trustManagers, SecureRandom())
        return sslContext
    }

    private fun createClientSslContext(): SSLContext {
        // Use default trust manager (validate real server certificates)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, null, SecureRandom())
        return sslContext
    }

    /**
     * A TLS handshake failed on one side of the MITM. [deviceSide] = the app rejected our
     * forged certificate (pinning, or it does not trust user CAs); otherwise the real
     * server could not be reached over TLS.
     */
    private class MitmHandshakeException(val deviceSide: Boolean, cause: SSLException) :
        Exception(cause.message, cause)

    private fun handleHandshakeFailure(session: Session, domain: String, e: MitmHandshakeException) {
        // Whichever side failed, retrying MITM for this host fails the same way every time:
        // the app retries, gets intercepted again, and never connects. BoringSSL messages
        // ("Read error: ssl=0x…: Failure in SSL library, usually a protocol error" +
        // "…SSLV3_ALERT_CERTIFICATE_UNKNOWN") vary too much to match reliably, so pass the
        // host through from now on regardless of the exact alert.
        pinningDetector.markAsPinned(domain)
        val who = if (e.deviceSide) "${session.ownerPackage ?: "app"} rejected the PrivacyGuard certificate"
                  else "upstream TLS to $domain failed"
        Log.w(TAG, "MITM handshake failed for $domain ($who) — passing this host through: ${e.cause?.message}")
        _statusFlow.value = MitmRuntimeStatus(
            state = "HANDSHAKE_FAILED",
            message = if (e.deviceSide)
                "$who (certificate pinning or user CAs not trusted) — $domain will be passed through without decryption"
            else
                "$who — $domain will be passed through without decryption",
            domain = domain,
            timestamp = System.currentTimeMillis(),
        )
        session.isMitmIntercepted = false
    }

    fun shutdown() {
        coroutineScope.cancel()
        certForger.clearCache()
    }
}
