package com.privacyguard.core.session

/**
 * TCP connection state machine.
 *
 * Transitions:
 * SYN_RECEIVED → SYN_ACK_SENT → ESTABLISHED → FIN_WAIT → CLOSE_WAIT → CLOSED
 */
enum class TcpState {
    /** SYN seen from device — connecting to remote server. */
    SYN_RECEIVED,
    /** SYN-ACK sent back to device — awaiting device ACK. */
    SYN_ACK_SENT,
    /** Three-way handshake complete — data flows freely. */
    ESTABLISHED,
    /** FIN received from device — waiting for server FIN. */
    FIN_WAIT,
    /** FIN received from server — sending FIN-ACK to device. */
    CLOSE_WAIT,
    /** Both sides closed — session eligible for cleanup. */
    CLOSED,
}