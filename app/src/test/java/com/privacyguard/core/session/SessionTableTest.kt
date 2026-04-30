package com.privacyguard.core.session

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SessionTableTest {

    private lateinit var table: SessionTable

    private fun key(srcPort: Int = 10000, dstPort: Int = 443, proto: Int = SessionKey.PROTO_TCP) =
        SessionKey.of("10.0.0.1", srcPort, "1.2.3.4", dstPort, proto)

    @Before
    fun setUp() {
        table = SessionTable(
            tcpTimeoutMs      = 5 * 60 * 1_000L,
            udpTimeoutMs      = 30 * 1_000L,
            reaperIntervalMs  = 60 * 1_000L,   // long interval so reaper never fires in tests
        )
    }

    // ── getOrCreate ───────────────────────────────────────────────────────────

    @Test
    fun getOrCreate_returns_new_session_for_unknown_key() {
        val k = key()
        val s = table.getOrCreate(k, uid = 1001, ownerPackage = "com.example")
        assertEquals(k,           s.key)
        assertEquals(1001,        s.ownerUid)
        assertEquals("com.example", s.ownerPackage)
        assertEquals(1,           table.size)
    }

    @Test
    fun getOrCreate_returns_same_session_for_same_key() {
        val k = key()
        val s1 = table.getOrCreate(k)
        val s2 = table.getOrCreate(k)
        assertSame(s1, s2)
        assertEquals(1, table.size)
    }

    @Test
    fun getOrCreate_different_keys_create_different_sessions() {
        table.getOrCreate(key(srcPort = 1))
        table.getOrCreate(key(srcPort = 2))
        assertEquals(2, table.size)
    }

    // ── get ───────────────────────────────────────────────────────────────────

    @Test
    fun get_returns_null_for_unknown_key() {
        assertNull(table.get(key()))
    }

    @Test
    fun get_returns_session_after_getOrCreate() {
        val k = key()
        val s = table.getOrCreate(k)
        assertSame(s, table.get(k))
    }

    // ── remove ────────────────────────────────────────────────────────────────

    @Test
    fun remove_deletes_session_and_marks_it_closed() {
        val k = key()
        val s = table.getOrCreate(k)
        table.remove(k)
        assertNull(table.get(k))
        assertTrue(s.isClosed)
        assertEquals(0, table.size)
    }

    @Test
    fun remove_is_noop_for_unknown_key() {
        table.remove(key())   // should not throw
        assertEquals(0, table.size)
    }

    // ── reap ──────────────────────────────────────────────────────────────────

    @Test
    fun reap_evicts_sessions_marked_as_closing() {
        val s = table.getOrCreate(key())
        table.markClosing(s)
        val evicted = table.reap()
        assertEquals(1, evicted)
        assertEquals(0, table.size)
    }

    @Test
    fun reap_evicts_sessions_already_closed() {
        val s = table.getOrCreate(key())
        s.isClosed = true
        assertEquals(1, table.reap())
        assertEquals(0, table.size)
    }

    @Test
    fun reap_evicts_timed_out_sessions() {
        // Create a table with a very short timeout
        val shortTable = SessionTable(tcpTimeoutMs = 0, udpTimeoutMs = 0, reaperIntervalMs = 60_000)
        val s = shortTable.getOrCreate(key())
        // Force lastActivityAt to the past
        s.lastActivityAt = System.currentTimeMillis() - 1_000
        assertEquals(1, shortTable.reap())
    }

    @Test
    fun reap_does_not_evict_healthy_sessions() {
        table.getOrCreate(key(srcPort = 1))
        table.getOrCreate(key(srcPort = 2))
        assertEquals(0, table.reap())
        assertEquals(2, table.size)
    }

    @Test
    fun reap_returns_count_of_evicted_sessions() {
        val s1 = table.getOrCreate(key(srcPort = 1))
        val s2 = table.getOrCreate(key(srcPort = 2))
        table.getOrCreate(key(srcPort = 3))   // healthy
        s1.isClosed  = true
        s2.isClosing = true
        assertEquals(2, table.reap())
        assertEquals(1, table.size)
    }

    // ── listeners ─────────────────────────────────────────────────────────────

    @Test
    fun listener_receives_onSessionCreated_event() {
        val created = mutableListOf<Session>()
        table.addListener(object : SessionTable.Listener {
            override fun onSessionCreated(session: Session) { created += session }
            override fun onSessionClosed(session: Session) {}
        })
        val s = table.getOrCreate(key())
        assertEquals(1, created.size)
        assertSame(s, created[0])
    }

    @Test
    fun listener_receives_onSessionClosed_on_remove() {
        val closed = mutableListOf<Session>()
        table.addListener(object : SessionTable.Listener {
            override fun onSessionCreated(session: Session) {}
            override fun onSessionClosed(session: Session) { closed += session }
        })
        val k = key()
        val s = table.getOrCreate(k)
        table.remove(k)
        assertEquals(1, closed.size)
        assertSame(s, closed[0])
    }

    @Test
    fun listener_receives_onSessionClosed_when_reaped() {
        val closed = mutableListOf<Session>()
        table.addListener(object : SessionTable.Listener {
            override fun onSessionCreated(session: Session) {}
            override fun onSessionClosed(session: Session) { closed += session }
        })
        val s = table.getOrCreate(key())
        s.isClosed = true
        table.reap()
        assertEquals(1, closed.size)
    }

    @Test
    fun removed_listener_stops_receiving_events() {
        var count = 0
        val listener = object : SessionTable.Listener {
            override fun onSessionCreated(session: Session) { count++ }
            override fun onSessionClosed(session: Session) {}
        }
        table.addListener(listener)
        table.getOrCreate(key(srcPort = 1))
        table.removeListener(listener)
        table.getOrCreate(key(srcPort = 2))
        assertEquals(1, count)   // only first create notified
    }

    // ── queries ───────────────────────────────────────────────────────────────

    @Test
    fun sessionsForUid_returns_only_matching_sessions() {
        table.getOrCreate(key(srcPort = 1), uid = 1001)
        table.getOrCreate(key(srcPort = 2), uid = 1001)
        table.getOrCreate(key(srcPort = 3), uid = 9999)
        assertEquals(2, table.sessionsForUid(1001).size)
        assertEquals(1, table.sessionsForUid(9999).size)
        assertEquals(0, table.sessionsForUid(0).size)
    }

    @Test
    fun search_finds_by_destination_ip() {
        table.getOrCreate(key(dstPort = 80))
        val results = table.search("1.2.3.4")
        assertEquals(1, results.size)
    }

    @Test
    fun search_finds_by_owner_package() {
        table.getOrCreate(key(), ownerPackage = "com.example.chat")
        assertEquals(1, table.search("chat").size)
        assertEquals(0, table.search("instagram").size)
    }

    // ── trafficStats ─────────────────────────────────────────────────────────

    @Test
    fun trafficStats_aggregates_bytes_from_all_sessions() {
        val s1 = table.getOrCreate(key(srcPort = 1))
        val s2 = table.getOrCreate(key(srcPort = 2))
        s1.bytesFromDevice.addAndGet(100)
        s1.bytesToDevice.addAndGet(200)
        s2.bytesFromDevice.addAndGet(50)
        val stats = table.trafficStats()
        assertEquals(2,   stats.activeSessions)
        assertEquals(150L, stats.totalBytesUp)
        assertEquals(200L, stats.totalBytesDown)
    }

    // ── clear ─────────────────────────────────────────────────────────────────

    @Test
    fun clear_removes_all_sessions_and_fires_closed_events() {
        val closed = mutableListOf<Session>()
        table.addListener(object : SessionTable.Listener {
            override fun onSessionCreated(session: Session) {}
            override fun onSessionClosed(session: Session) { closed += session }
        })
        table.getOrCreate(key(srcPort = 1))
        table.getOrCreate(key(srcPort = 2))
        table.clear()
        assertEquals(0, table.size)
        assertTrue(table.isEmpty)
        assertEquals(2, closed.size)
    }
}
