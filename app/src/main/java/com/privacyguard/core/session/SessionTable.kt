package com.privacyguard.core.session

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    /** How many sessions may exist at once: see [Limits]. */
    private val limits: Limits = Limits(),
) {
    /**
     * Each session holds a socket. Without a bound, one app sending to many ports used up
     * the process's file descriptors and every connection on the phone failed. Normal
     * browsing peaked near 120 sessions (14 heavy pages open, emulator, 2026-11-11), so
     * these leave a wide margin. [maxPerApp] counts TCP and UDP together, so one app
     * can't take every slot.
     */
    data class Limits(
        val maxTcp: Int = 4_096,
        val maxUdp: Int = 4_096,
        val maxPerApp: Int = 1_024,
    )

    /** Told when a new flow is refused at a limit, or an idle UDP session closed to make room. */
    interface LimitListener {
        fun onRefused(key: SessionKey)
        fun onEvicted(key: SessionKey)
    }

    @Volatile var limitListener: LimitListener? = null

    private val tcpCount = AtomicInteger()
    private val udpCount = AtomicInteger()
    private val perApp = ConcurrentHashMap<Int, AtomicInteger>()

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
    fun getOrCreate(key: SessionKey, uid: Int = -1, ownerPackage: String? = null): Session =
        sessions[key] ?: insert(key, uid, ownerPackage)

    /**
     * Like [getOrCreate], within [limits]. At a limit the oldest idle UDP sessions in that
     * scope are closed to make room (never TCP: that would cut a live connection); if none
     * can be, returns null and the caller refuses the flow (fail closed). Only the TUN
     * reader thread creates sessions, so the check and the insert don't race each other.
     */
    fun tryCreate(key: SessionKey, uid: Int = -1, ownerPackage: String? = null): Session? {
        sessions[key]?.let { return it }
        val udp = key.protocol == SessionKey.PROTO_UDP
        val protoCount = if (udp) udpCount else tcpCount
        val protoMax = if (udp) limits.maxUdp else limits.maxTcp
        if (protoCount.get() >= protoMax && !(udp && makeRoom(forUid = null))) return refuse(key)
        if (appCount(uid) >= limits.maxPerApp && !makeRoom(forUid = uid)) return refuse(key)
        return insert(key, uid, ownerPackage)
    }

    private fun refuse(key: SessionKey): Session? {
        limitListener?.onRefused(key)
        return null
    }

    private fun appCount(uid: Int): Int = perApp[uid]?.get() ?: 0

    private fun insert(key: SessionKey, uid: Int, ownerPackage: String?): Session {
        var created = false
        val session = sessions.computeIfAbsent(key) {
            created = true
            Session(key = key, ownerUid = uid, ownerPackage = ownerPackage)
        }
        if (created) {
            (if (key.protocol == SessionKey.PROTO_UDP) udpCount else tcpCount).incrementAndGet()
            perApp.getOrPut(uid) { AtomicInteger() }.incrementAndGet()
            listeners.forEach { it.onSessionCreated(session) }
        }
        return session
    }

    /** Bookkeeping for a session that just left the map (called once per session). */
    private fun released(session: Session) {
        (if (session.key.protocol == SessionKey.PROTO_UDP) udpCount else tcpCount).decrementAndGet()
        perApp[session.limitUid]?.decrementAndGet()
    }

    /**
     * Closes up to [EVICT_BATCH] of the oldest UDP sessions idle for at least [MIN_IDLE_MS]
     * ([forUid]'s only, or any app's when null). A batch, so a flood at the limit scans
     * the table once per batch instead of once per packet. True when one was closed.
     */
    private fun makeRoom(forUid: Int?): Boolean {
        val now = System.currentTimeMillis()
        val idle = sessions.values.filter {
            it.key.protocol == SessionKey.PROTO_UDP && (forUid == null || it.limitUid == forUid) &&
                now - it.lastActivityAt >= MIN_IDLE_MS
        }
        if (idle.isEmpty()) return false
        var closed = 0
        for (s in idle.sortedBy { it.lastActivityAt }.take(EVICT_BATCH)) {
            if (sessions.remove(s.key, s)) {
                released(s)
                s.close()
                listeners.forEach { it.onSessionClosed(s) }
                limitListener?.onEvicted(s.key)
                closed++
            }
        }
        return closed > 0
    }

    /**
     * Explicitly removes and closes the session identified by [key].
     * No-op if no session exists for [key].
     */
    fun remove(key: SessionKey) {
        val session = sessions.remove(key) ?: return
        released(session)
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

    // Created by startReaper and shut down by stopReaper: a cancelled task leaves an
    // idle executor's thread alive for good, one more after every start of protection.
    private var reaperExecutor: ScheduledExecutorService? = null

    /**
     * Starts the background reaper that evicts timed-out / closed sessions.
     * Call this when the VPN service starts.
     */
    @Synchronized
    fun startReaper() {
        stopReaper()
        val executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "session-reaper").also { it.isDaemon = true }
        }
        reaperExecutor = executor
        executor.scheduleWithFixedDelay(
            // A scheduled task that throws is never run again: idle sessions would pile up.
            { com.privacyguard.vpn.WorkerGuard.guard("session-reaper") { reap() } },
            reaperIntervalMs,
            reaperIntervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * Stops the background reaper.
     * Call this when the VPN service stops.
     */
    @Synchronized
    fun stopReaper() {
        // Not shutdownNow: interrupting a running reap could fail it half way.
        reaperExecutor?.shutdown()
        reaperExecutor = null
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
                released(session)
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
        tcpCount.set(0)
        udpCount.set(0)
        perApp.clear()
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

    private companion object {
        /** A UDP session quiet this long may be closed to make room. */
        const val MIN_IDLE_MS = 5_000L
        const val EVICT_BATCH = 64
    }
}