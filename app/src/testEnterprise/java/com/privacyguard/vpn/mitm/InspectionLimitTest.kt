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

/**
 * HTTPS inspection decrypts at most MAX_PARALLEL_INSPECTIONS connections at once.
 * Above that a connection is passed through undecrypted and counted, and every
 * way an interception can end gives its slot back.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class InspectionLimitTest {

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

    private fun interception(): MitmTlsInterception {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null) as sun.misc.Unsafe
        // The engine's early exits (no name) run before any of its fields are used.
        val engine = unsafe.allocateInstance(MitmEngine::class.java) as MitmEngine
        return MitmTlsInterception(
            engine, PinningDetector(), MitmConfig(context), PayloadParser(PiiRedactor()),
            PayloadShipper.getInstance(context), repo, ensureCa = { true }, ownUid = 1_000,
        )
    }

    @Before fun setUp() = StatsManager.reset()

    private fun fill(i: MitmTlsInterception) =
        repeat(MitmEngine.MAX_PARALLEL_INSPECTIONS) { assertTrue(i.admitForInspection()) }

    private fun counted(): Long { StatsManager.publishNow(); return StatsManager.snapshot.value.inspectionAtLimit }

    @Test fun above_the_limit_connections_pass_through_and_are_counted() {
        val i = interception()
        fill(i)
        assertFalse(i.admitForInspection())
        assertFalse(i.admitForInspection())
        assertEquals(2L, counted())
    }

    @Test fun a_redirect_that_never_started_gives_its_slot_back() {
        val i = interception()
        fill(i)
        i.interceptNotStarted()
        assertTrue(i.admitForInspection())
        assertEquals(0L, counted())
    }

    @Test fun an_interception_that_ends_early_gives_its_slot_back() {
        val i = interception()
        fill(i)
        val noName = Session(SessionKey.of("10.0.0.2", 40_000, "93.184.216.34", 443, 6), ownerUid = 10_123)
        assertEquals(-1, i.intercept(noName, 40_001))
        assertTrue(i.admitForInspection())
        assertFalse(i.admitForInspection())
    }
}
