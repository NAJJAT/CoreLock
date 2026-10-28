package com.privacyguard.vpn.mitm

import android.content.Context
import com.privacyguard.app.data.db.PayloadLogEntity
import com.privacyguard.core.metadata.EncryptionStatus
import com.privacyguard.core.session.Session
import com.privacyguard.core.session.SessionKey
import com.privacyguard.domain.repository.PayloadLogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Payloads are captured only while inspection is switched on AND consented to, the
 * same rule decryption and the status shown to the user follow. With the switch on
 * but consent expired or withdrawn, the UI says "off": nothing may be logged or
 * queued for the SIEM.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class CaptureNeedsConsentTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val config = MitmConfig(context)
    // A fresh shipper per test: the singleton would keep the first test's preferences
    // (Robolectric gives every test a new application).
    private val shipper = PayloadShipper::class.java.getDeclaredField("INSTANCE")
        .apply { isAccessible = true }.set(null, null)
        .let { PayloadShipper.getInstance(context) }
    private val saved = CopyOnWriteArrayList<PayloadLogEntity>()

    private val repo = object : PayloadLogRepository {
        override fun recentLogs(limit: Int): Flow<List<PayloadLogEntity>> = emptyFlow()
        override fun filtered(query: String, method: String?, direction: String?, hasBody: Boolean, limit: Int): Flow<List<PayloadLogEntity>> = emptyFlow()
        override fun summaries(): Flow<List<com.privacyguard.app.data.db.PayloadLogSummary>> = emptyFlow()
        override suspend fun logsForSession(sessionId: String) = emptyList<PayloadLogEntity>()
        override suspend fun logsForPackage(pkg: String, limit: Int) = emptyList<PayloadLogEntity>()
        override suspend fun search(query: String) = emptyList<PayloadLogEntity>()
        override suspend fun saveLog(log: PayloadLogEntity): Long { saved += log; return saved.size.toLong() }
        override suspend fun deleteOlderThan(days: Int) = Unit
        override suspend fun getTotalCount() = saved.size
    }

    @Before
    fun setUp() {
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(
                context,
                androidx.work.Configuration.Builder().setExecutor { it.run() }.build(),
            )
        }
        config.setShipToSiem(true)   // no endpoint: events stay queued, countable
    }

    @After
    fun tearDown() {
        config.setShipToSiem(false)
        config.setEnabled(false)
        config.clearConsent()
    }

    private fun interception(): MitmTlsInterception {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null) as sun.misc.Unsafe
        // Not used on the capture path.
        val engine = unsafe.allocateInstance(MitmEngine::class.java) as MitmEngine
        return MitmTlsInterception(
            engine, PinningDetector(), config, PayloadParser(PiiRedactor()), shipper, repo,
            ensureCa = { true }, ownUid = 1_000,
        )
    }

    private fun cleartextSession() =
        Session(SessionKey.of("10.0.0.2", 40_000, "93.184.216.34", 80, 6), ownerUid = 10_123, ownerPackage = "com.example")
            .also { it.encryptionStatus = EncryptionStatus.CLEARTEXT; it.tlsSni = "example.com" }

    private val request = "GET /a HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()

    private fun waitFor(ms: Long, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (done()) return true; Thread.sleep(20) }
        return done()
    }

    private fun expireUserConsent() {
        MitmConfig.prefs(context).edit()
            .putLong("mitm_consent_timestamp", System.currentTimeMillis() - 91L * 24 * 60 * 60 * 1000)
            .putBoolean("mitm_consent_by_org", false)
            .commit()
    }

    /** What TcpForwarder gates on, and what the second lock inside must refuse. */
    private fun assertNothingCaptured(i: MitmTlsInterception) {
        val pending = shipper.pendingCount()
        i.capture("OUTBOUND", request, cleartextSession())
        i.logNotDecrypted(cleartextSession(), 512)
        Thread.sleep(1_500)
        assertTrue("saved without consent: ${saved.map { it.bodyEncoding }}", saved.isEmpty())
        assertEquals(pending, shipper.pendingCount())
        assertFalse("capture gate open without valid consent", i.isEnabled)
    }

    @Test
    fun control_with_valid_consent_the_request_is_logged_and_queued() {
        config.setEnabled(true)
        config.recordConsent()
        val i = interception()
        assertTrue(i.isEnabled)
        val pending = shipper.pendingCount()
        i.capture("OUTBOUND", request, cleartextSession())
        assertTrue(waitFor(5_000) { saved.size == 1 })
        assertTrue(waitFor(5_000) { shipper.pendingCount() == pending + 1 })
    }

    @Test
    fun consent_older_than_90_days_captures_nothing() {
        config.setEnabled(true)
        expireUserConsent()
        assertNothingCaptured(interception())
    }

    @Test
    fun consent_withdrawn_by_mdm_captures_nothing() {
        config.setEnabled(true, byOrganisation = true)
        config.recordConsent(byOrganisation = true)
        config.clearConsent()   // what ManagedConfigApplier does when the policy says false
        assertNothingCaptured(interception())
    }
}
