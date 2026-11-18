package com.privacyguard.vpn.mitm

import android.content.Context
import com.privacyguard.app.core.stats.StatsManager
import com.privacyguard.app.data.db.PayloadLogEntity
import com.privacyguard.core.session.Session
import com.privacyguard.core.session.SessionKey
import com.privacyguard.domain.repository.PayloadLogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Bug-fix round 2 (S-5, S-6):
 * - HTTPS inspection stops with protection: no new interception, open ones closed, its
 *   workers and certificate cache gone. It used to keep running after the VPN stopped.
 * - Everything inspection holds in memory (chunks waiting, messages being reassembled,
 *   messages waiting to be parsed) shares one byte cap. Above it a new connection goes to
 *   its server undecrypted (its name was already checked) and is counted.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class InspectionStopAndBudgetTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private val repo = object : PayloadLogRepository {
        override fun recentLogs(limit: Int): Flow<List<PayloadLogEntity>> = emptyFlow()
        override fun filtered(query: String, method: String?, direction: String?, hasBody: Boolean, limit: Int): Flow<List<PayloadLogEntity>> = emptyFlow()
        override fun summaries(): Flow<List<com.privacyguard.app.data.db.PayloadLogSummary>> = emptyFlow()
        override suspend fun logsForSession(sessionId: String) = emptyList<PayloadLogEntity>()
        override suspend fun logsForPackage(pkg: String, limit: Int) = emptyList<PayloadLogEntity>()
        override suspend fun search(query: String) = emptyList<PayloadLogEntity>()
        override suspend fun saveLog(log: PayloadLogEntity): Long = 0
        override suspend fun deleteOlderThan(days: Int) = Unit
        override suspend fun getTotalCount() = 0
    }

    private fun engine(): MitmEngine {
        // No Android keystore under Robolectric; these paths never use the CA.
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
        val ca = unsafe.allocateInstance(CaManager::class.java) as CaManager
        return MitmEngine(CertForger(ca), PinningDetector(), ca, PayloadParser(PiiRedactor()))
    }

    private fun interception(engine: MitmEngine = engine(), maxBytes: Long = 1_000) = MitmTlsInterception(
        engine, PinningDetector(), MitmConfig(context), PayloadParser(PiiRedactor()),
        PayloadShipper.getInstance(context), repo, ensureCa = { true }, ownUid = 1_000,
        maxBufferedBytes = maxBytes,
    )

    private val session = Session(SessionKey.of("10.0.0.2", 40_000, "93.184.216.34", 443, 6), ownerUid = 10_123)

    @Before fun setUp() = StatsManager.reset()

    private fun atLimit(): Long { StatsManager.publishNow(); return StatsManager.snapshot.value.inspectionAtLimit }

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError(what)
            Thread.sleep(10)
        }
    }

    // ── S-5: stop ────────────────────────────────────────────────────────────

    @Test fun after_shutdown_nothing_new_is_inspected_and_it_is_not_counted_as_the_limit() {
        val i = interception()
        assertTrue(i.admitForInspection())
        i.shutdown()
        assertFalse(i.admitForInspection())
        assertFalse(i.shouldIntercept(10_123, "com.example", "example.com"))
        i.capture("OUTBOUND", ByteArray(10), session)          // must not throw
        assertEquals("a stopped engine is not a full one", 0L, atLimit())
        assertEquals(0L, i.bufferedBytes)
    }

    @Test fun the_engine_closes_what_it_holds_open_and_refuses_new_work() {
        val e = engine()
        val held = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        assertTrue(e.track(held))
        e.shutdown()
        assertTrue("an open interception socket is closed", held.isClosed)

        var done = 0
        val named = Session(SessionKey.of("10.0.0.2", 40_002, "93.184.216.34", 443, 6), ownerUid = 10_123).apply { tlsSni = "example.com" }
        assertEquals(-1, e.intercept(named, 40_003, onDone = { done++ }) { _, _, _ -> })
        assertEquals("its slot is still given back", 1, done)
        val late = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        assertFalse("a socket opened during shutdown is refused", e.track(late))
        assertTrue(late.isClosed)
    }

    /** The service stops inspection with protection, and before it builds a new one. */
    @Test fun the_service_stops_inspection_when_protection_stops() {
        val src = File("src/main/java/com/privacyguard/platform/android/PrivacyVpnService.kt").readText()
        val release = src.substringAfter("private fun releaseResourcesLocked()").substringBefore("\n    }\n")
        assertTrue("releaseResourcesLocked shuts inspection down", release.contains("interception.shutdown()"))
        val create = src.substringBefore("interception = TlsInterceptionFactory.create(").takeLast(300)
        assertTrue("an earlier one is shut down first", create.contains("interception.shutdown()"))
    }

    // ── S-6: byte cap ────────────────────────────────────────────────────────

    @Test fun above_the_byte_cap_new_connections_pass_through_and_are_counted() {
        val i = interception(maxBytes = 1_000)
        // A request whose headers never end: it stays in the reassembly buffer.
        i.capture("OUTBOUND", ("GET / HTTP/1.1\r\nHost: example.com\r\nX: " + "a".repeat(1_100)).toByteArray(), session)
        assertEquals("over the cap: the chunk is not held", 0L, i.bufferedBytes)
        assertEquals(1L, i.droppedCaptures.get())

        i.capture("OUTBOUND", ("GET / HTTP/1.1\r\nHost: example.com\r\nX: " + "a".repeat(900)).toByteArray(), session)
        waitFor("the chunk reaches the buffer") { i.bufferedBytes >= 900 }
        assertFalse("memory full: decryption is refused", i.admitForInspection())
        assertEquals(1L, atLimit())
        i.capture("OUTBOUND", ByteArray(200), session)
        assertEquals("and new chunks are dropped", 2L, i.droppedCaptures.get())
        i.shutdown()
    }

    @Test fun bytes_are_given_back_when_a_message_is_done_and_when_a_connection_ends() {
        val i = interception(maxBytes = 100_000)
        i.capture("OUTBOUND", "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray(), session)
        waitFor("a complete message frees its bytes") { i.bufferedBytes == 0L }

        i.capture("INBOUND", "HTTP/1.1 200 OK\r\nContent-Length: 50000\r\n\r\npartial".toByteArray(), session)
        waitFor("the partial reply is held") { i.bufferedBytes > 0L }
        i.captureEnded(session)
        waitFor("the end of the connection frees it") { i.bufferedBytes == 0L }
        assertTrue(i.admitForInspection())
        i.shutdown()
    }
}
