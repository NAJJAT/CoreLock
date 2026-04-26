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
    private val payloadParser: PayloadParser
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
     * Intercept TLS traffic for a session
     *
     * @param session The VPN session (already classified as TLS)
     * @param onPayload Callback for each payload chunk (direction, bytes, session)
     *
     * @return true if interception started successfully, false otherwise
     */
    fun intercept(
        session: Session,
        onPayload: (direction: String, bytes: ByteArray, session: Session) -> Unit
    ): Boolean {
        val domain = session.tlsSni ?: return false

        // Check if we should skip due to pinning
        if (pinningDetector.isPinned(session.ownerPackage, domain)) {
            Log.d(TAG, "Skipping MITM for pinned domain: $domain")
            // ADDED
            _statusFlow.value = MitmRuntimeStatus(
                state = "PINNED_BYPASS",
                message = "Pinned app/domain bypassed interception",
                domain = domain,
                timestamp = System.currentTimeMillis(),
            )
            return false
        }

        // Mark session as intercepted
        session.isMitmIntercepted = true

        // Launch interception coroutine
        coroutineScope.launch {
            try {
                // ADDED
                _statusFlow.value = MitmRuntimeStatus(
                    state = "STARTING",
                    message = "Starting interception",
                    domain = domain,
                    timestamp = System.currentTimeMillis(),
                )
                performInterception(session, domain, onPayload)
                // ADDED
                _statusFlow.value = MitmRuntimeStatus(
                    state = "ACTIVE",
                    message = "Interception active",
                    domain = domain,
                    timestamp = System.currentTimeMillis(),
                )
            } catch (e: SSLHandshakeException) {
                // Handle pinning detection failure
                handleHandshakeFailure(session, domain, e)
            } catch (e: Exception) {
                Log.e(TAG, "MITM interception failed for ${session.key}", e)
                // ADDED
                _statusFlow.value = MitmRuntimeStatus(
                    state = "ERROR",
                    message = e.message ?: e::class.java.simpleName,
                    domain = domain,
                    timestamp = System.currentTimeMillis(),
                )
                session.isMitmIntercepted = false
            }
        }

        return true
    }

    private suspend fun performInterception(
        session: Session,
        domain: String,
        onPayload: (String, ByteArray, Session) -> Unit
    ) = withContext(Dispatchers.IO) {

        // Setup device-side SSL server socket (presenting forged cert)
        val serverSslContext = createServerSslContext(domain)
        val serverSocket = serverSslContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        serverSocket.use {
            // Setup real server-side SSL socket
            val clientSslContext = createClientSslContext()
            val clientSocket = clientSslContext.socketFactory.createSocket() as SSLSocket
            clientSocket.connect(InetSocketAddress(domain, 443))

            // Set SNI for real connection
            val params = clientSocket.sslParameters
            params.serverNames = listOf(SNIHostName(domain))
            clientSocket.sslParameters = params
            clientSocket.startHandshake()

            // Accept device connection
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
