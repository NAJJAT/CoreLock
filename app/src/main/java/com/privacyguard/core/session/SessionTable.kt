package com.privacyguard.core.session

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The central connection registry.
 *
 * Maps [SessionKey] → [Session] for every active proxied connection.
 * This is the most performance-critical data structure in the VPN engine:
 * it is read on every single packet that flows through the TUN interface.
 *
 * Design choices:
 *  - [ConcurrentHashMap] for O(1) average lookup with low lock contention.
 *  - A background reaper thread periodically evicts timed-out sessions.
 *  - Listeners are notified on session create/close for live UI updates.
 *
 * Thread safety: all public methods are thread-safe.
 */
class SessionTable(
    /** TCP session timeout in milliseconds (default 5 minutes). */
    private val tcpTimeoutMs: Long = 5 * 60 * 1_000L,
    /** UDP session timeout in milliseconds (default 30 seconds). */
    private val udpTimeoutMs: Long = 30 * 1_000L,
    /** How often the reaper runs (default 15 seconds). */
    private val reaperIntervalMs: Long = 15 * 1_000L,
) {
    // ─────────────────────────────────────────────────────────────────────────
    // Storage
    // ─────────────────────────────────────────────────────────────────────────

    private val sessions = ConcurrentHashMap<SessionKey, Session>(256)

    // ─────────────────────────────────────────────────────────────────────────
    // Listeners
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Callback interface for session lifecycle events.
     * Implementations must be fast (called on the VPN engine thread).
     */
    interface Listener {
        fun onSessionCreated(session: Session)
        fun onSessionClosed(session: Session)
    }

    private val listeners = ConcurrentHashMap.newKeySet<Listener>()

    fun addListener(listener: Listener)    { listeners.add(listener) }
    fun removeListener(listener: Listener) { listeners.remove(listener) }

    // ─────────────────────────────────────────────────────────────────────────
    // CRUD
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the [Session] for [key], or null if none exists.
     * Fastest path — called on every packet.
     */
    fun get(key: SessionKey): Session? = sessions[key]

    /**
     * Creates a new [Session] for [key] if it doesn't already exist,
     * or returns the existing one if it does (compare-and-set semantics).
     *
     * @param uid           UID of the owning Android app.
     * @param ownerPackage  package name of the owning app.
     * @return the new or existing [Session].
     */
    fun getOrCreate(key: SessionKey, uid: Int = -1, ownerPackage: String? = null): Session {
        return sessions.getOrPut(key) {
            val session = Session(key = key, ownerUid = uid, ownerPackage = ownerPackage)
            listeners.forEach { it.onSessionCreated(session) }
            session
        }
    }

    /**
     * Explicitly removes and closes the session identified by [key].
     * No-op if no session exists for [key].
     */
    fun remove(key: SessionKey) {
        val session = sessions.remove(key) ?: return
        session.close()
        listeners.forEach { it.onSessionClosed(session) }
    }

    /**
     * Marks [session] as closing and schedules it for removal.
     * The session will still be readable until the reaper runs.
     */
    fun markClosing(session: Session) {
        session.isClosing = true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Queries
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns the current number of active sessions. */
    val size: Int get() = sessions.size

    /** Returns true if there are no active sessions. */
    val isEmpty: Boolean get() = sessions.isEmpty()

    /** Returns a snapshot list of all sessions, sorted by creation time (newest first). */
    fun allSessions(): List<Session> =
        sessions.values.sortedByDescending { it.createdAt }

    /** Returns snapshots of all sessions safe for off-thread consumption. */
    fun allSnapshots(): List<SessionSnapshot> =
        sessions.values.map { it.snapshot() }

    /**
     * Returns all sessions belonging to [uid].
     */
    fun sessionsForUid(uid: Int): List<Session> =
        sessions.values.filter { it.ownerUid == uid }

    /**
     * Returns all sessions whose destination IP or hostname matches [query].
     */
    fun search(query: String): List<Session> {
        val q = query.lowercase()
        return sessions.values.filter {
            it.key.destinationIp.contains(q) ||
            it.hostname?.lowercase()?.contains(q) == true ||
            it.ownerPackage?.lowercase()?.contains(q) == true
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reaper
    // ─────────────────────────────────────────────────────────────────────────

    private val reaperExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "session-reaper").also { it.isDaemon = true }
    }

    private var reaperFuture: ScheduledFuture<*>? = null

    /**
     * Starts the background reaper that evicts timed-out / closed sessions.
     * Call this when the VPN service starts.
     */
    fun startReaper() {
        reaperFuture = reaperExecutor.scheduleWithFixedDelay(
            ::reap,
            reaperIntervalMs,
            reaperIntervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * Stops the background reaper.
     * Call this when the VPN service stops.
     */
    fun stopReaper() {
        reaperFuture?.cancel(false)
        reaperFuture = null
    }

    /**
     * Evicts all sessions that are already closed or have exceeded their protocol timeout.
     * Called automatically by the reaper; can also be called manually for testing.
     *
     * @return the number of sessions evicted.
     */
    fun reap(): Int {
        var evicted = 0
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val (key, session) = iterator.next()
            val timeout = if (key.protocol == SessionKey.PROTO_UDP) udpTimeoutMs else tcpTimeoutMs
            if (session.isClosed || session.isClosing || session.isTimedOut(timeout)) {
                iterator.remove()
                session.close()
                listeners.forEach { it.onSessionClosed(session) }
                evicted++
            }
        }
        return evicted
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shutdown
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Closes all sessions and shuts down the reaper.
     * Call when the VPN service is fully stopped.
     */
    fun clear() {
        stopReaper()
        val snapshot = sessions.values.toList()
        sessions.clear()
        snapshot.forEach { session ->
            session.close()
            listeners.forEach { it.onSessionClosed(session) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stats
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Aggregated traffic stats across all current sessions.
     */
    data class TrafficStats(
        val activeSessions:   Int,
        val totalBytesUp:     Long,
        val totalBytesDown:   Long,
        val totalPacketsUp:   Long,
        val totalPacketsDown: Long,
    )

    fun trafficStats(): TrafficStats {
        var bytesUp     = 0L
        var bytesDown   = 0L
        var packetsUp   = 0L
        var packetsDown = 0L
        sessions.values.forEach { s ->
            bytesUp     += s.bytesFromDevice.get()
            bytesDown   += s.bytesToDevice.get()
            packetsUp   += s.packetsFromDevice.get()
            packetsDown += s.packetsToDevice.get()
        }
        return TrafficStats(sessions.size, bytesUp, bytesDown, packetsUp, packetsDown)
    }

    override fun toString(): String = "SessionTable(active=${sessions.size})"
}