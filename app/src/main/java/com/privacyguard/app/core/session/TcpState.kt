/**
 * TcpState.kt
 *
 * TCP states according to RFC 793
 *
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.core.session

enum class TcpState {
    CLOSED,          // No connection (initial state)
    LISTEN,          // Server waiting for connection
    SYN_SENT,        // Client sent SYN, waiting for SYN-ACK
    SYN_RECEIVED,    // Received SYN-ACK, waiting for client ACK
    ESTABLISHED,     // Handshake complete - data can flow
    FIN_WAIT_1,      // Sent FIN, waiting for ACK
    FIN_WAIT_2,      // Received ACK for FIN, waiting for peer FIN
    CLOSE_WAIT,      // Received FIN from peer, sent ACK
    LAST_ACK,        // Sent FIN after CLOSE_WAIT, waiting for ACK
    TIME_WAIT,       // Waiting before final close (2 * MSL)
    RESET            // Connection aborted (RST received or sent)
}