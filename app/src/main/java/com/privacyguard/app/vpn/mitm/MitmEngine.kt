package com.privacyguard.vpn.mitm

import android.util.Log
import com.privacyguard.core.session.Session
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
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
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Intercept TLS traffic for a session.
     *
     * Creates a local SSL server socket synchronously (so the port is known before returning),
     * then launches a coroutine to accept the device connection and proxy it to the real server.
     * TcpForwarder must redirect the session's NIO channel to this port after calling intercept().
     *
     * @param session The VPN session (already classified as TLS)
     * @param onPayload Callback for each payload chunk (direction, bytes, session)
     * @return local port the MITM server is listening on, or -1 if interception was skipped/failed
     */
    fun intercept(
        session: Session,
        onPayload: (direction: String, bytes: ByteArray, session: Session) -> Unit
    ): Int {
        val domain = session.tlsSni ?: return -1

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
            (ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket).also {
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
                performInterception(session, domain, serverSocket, onPayload)
                _statusFlow.value = MitmRuntimeStatus(
                    state = "ACTIVE",
                    message = "Interception active",
                    domain = domain,
                    timestamp = System.currentTimeMillis(),
                )
            } catch (e: SSLHandshakeException) {
                handleHandshakeFailure(session, domain, e)
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
        onPayload: (String, ByteArray, Session) -> Unit
    ) = withContext(Dispatchers.IO) {

        serverSocket.use {
            // Setup real server-side SSL socket, protected from the VPN tunnel so it
            // reaches the internet directly without looping back through TcpForwarder.
            val clientSslContext = createClientSslContext()
            val clientSocket = clientSslContext.socketFactory.createSocket() as SSLSocket
            protectSocket(clientSocket)
            clientSocket.connect(InetSocketAddress(domain, 443))

            // Set SNI for real connection
            val params = clientSocket.sslParameters
            params.serverNames = listOf(SNIHostName(domain))
            clientSocket.sslParameters = params
            clientSocket.startHandshake()

            // Accept device connection (TcpForwarder redirected the session channel here)
            val deviceSocket = it.accept()

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

                val payload = buffer.copyOf(bytesRead)

                // Call payload callback for inspection
                onPayload(direction, payload, session)

                // Forward to destination
                to.write(payload, 0, bytesRead)
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
                return arrayOf(cert)
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

    private fun handleHandshakeFailure(session: Session, domain: String, e: SSLHandshakeException) {
        if (e.message?.contains("certificate_unknown") == true ||
            e.message?.contains("bad_certificate") == true) {
            pinningDetector.markAsPinned(domain)
            Log.w(TAG, "Handshake failure likely due to pinning for $domain")
        }
        // ADDED
        _statusFlow.value = MitmRuntimeStatus(
            state = "HANDSHAKE_FAILED",
            message = e.message ?: "SSL handshake failed",
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
