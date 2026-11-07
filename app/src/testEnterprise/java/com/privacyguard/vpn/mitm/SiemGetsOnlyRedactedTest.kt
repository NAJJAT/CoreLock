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
 * Through the real capture path (MitmTlsInterception → PayloadParser → PayloadShipper): the
 * SIEM batch never holds a query value, form value, binary body or credential, whatever
 * "Redact PII" says; the local log holds them only while it is off.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class SiemGetsOnlyRedactedTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val config = MitmConfig(context)
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

    private val form = "user=alice@example.org&password=hunter2&addr=Main+Street"
    private val binary = ByteArray(64) { i -> (i * 37 + 0x81).toByte() }   // not text

    /** Values that must never reach the SIEM (the last: the start of the binary body as base64). */
    private val secrets = listOf("alice@example.org", "hunter2", "s3cr3tToken", "Main+Street", "c2VjcmV0",
        kotlin.io.encoding.Base64.encode(binary).take(12))

    private val messages = listOf(
        ("POST /login?token=s3cr3tToken HTTP/1.1\r\nHost: example.com\r\nCookie: sid=c2VjcmV0\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${form.length}\r\n\r\n$form")
            .toByteArray(Charsets.ISO_8859_1),
        ("POST /blob HTTP/1.1\r\nHost: example.com\r\nContent-Type: application/octet-stream\r\n" +
            "Content-Length: ${binary.size}\r\n\r\n").toByteArray(Charsets.ISO_8859_1) + binary,
    )

    @Before
    fun setUp() {
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(
                context, androidx.work.Configuration.Builder().setExecutor { it.run() }.build(),
            )
        }
        config.setShipToSiem(true)   // no endpoint: events stay queued, readable
        config.setEnabled(true)
        config.recordConsent()
    }

    @After
    fun tearDown() {
        config.setShipToSiem(false)
        config.setEnabled(false)
        config.setRedactPii(true)
        config.clearConsent()
    }

    private fun interception(): MitmTlsInterception {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null) as sun.misc.Unsafe
        val engine = unsafe.allocateInstance(MitmEngine::class.java) as MitmEngine   // not used on this path
        return MitmTlsInterception(
            engine, PinningDetector(), config, PayloadParser(PiiRedactor()), shipper, repo,
            ensureCa = { true }, ownUid = 1_000,
        )
    }

    private fun session(port: Int) =
        Session(SessionKey.of("10.0.0.2", port, "93.184.216.34", 80, 6), ownerUid = 10_123, ownerPackage = "com.example")
            .also { it.encryptionStatus = EncryptionStatus.CLEARTEXT; it.tlsSni = "example.com" }

    private fun waitFor(ms: Long, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (done()) return true; Thread.sleep(20) }
        return done()
    }

    /** Captures [messages], returns (SIEM JSON, local rows as text). */
    private fun captureAll(): Pair<String, String> {
        val i = interception()
        messages.forEachIndexed { n, m -> i.capture("OUTBOUND", m, session(40_000 + n)) }
        assertTrue("logged", waitFor(5_000) { saved.size == messages.size })
        assertTrue("queued", waitFor(5_000) { shipper.pendingEvents().size == messages.size })
        val json = shipper.jsonFor(shipper.pendingEvents())
        val local = saved.joinToString("\n") { listOf(it.urlPath, it.headers, it.body, it.bodyEncoding).joinToString(" ") }
        return json to local
    }

    @Test
    fun with_redact_pii_on_neither_the_siem_nor_the_local_log_gets_the_values() {
        config.setRedactPii(true)
        val (json, local) = captureAll()
        for (s in secrets) {
            assertFalse("SIEM got $s: $json", json.contains(s))
            assertFalse("local log got $s: $local", local.contains(s))
        }
        assertTrue(json.contains("token=[REDACTED]"))
        assertTrue(json.contains("[application/octet-stream body, 64 bytes, not stored]"))
    }

    @Test
    fun with_redact_pii_off_the_siem_still_gets_only_redacted_data() {
        config.setRedactPii(false)
        val (json, local) = captureAll()
        for (s in secrets) assertFalse("SIEM got $s: $json", json.contains(s))
        // Control: the local log really holds what left the phone, so the test can see a leak.
        assertTrue(local.contains("hunter2"))
        assertTrue(local.contains("s3cr3tToken"))
        assertEquals(messages.size, shipper.pendingEvents().size)
    }
}
