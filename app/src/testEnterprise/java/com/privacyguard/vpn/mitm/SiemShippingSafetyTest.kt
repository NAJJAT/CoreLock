package com.privacyguard.vpn.mitm

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * What the SIEM queue may send, and when (F-5):
 * - nothing once inspection is off or consent is gone, and the queue is dropped;
 * - events only to the endpoint they were queued for;
 * - a queue bounded in bytes, not only in events;
 * - after a failed upload, automatic retries wait longer each time.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class SiemShippingSafetyTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val config = MitmConfig(context)
    private val now = AtomicLong(1_000_000L)
    private val sent = CopyOnWriteArrayList<Pair<URI, String>>()
    private val succeed = AtomicBoolean(true)

    private val shipper = PayloadShipper(config, clock = { now.get() }) { endpoint, _, json ->
        sent += endpoint to json
        succeed.get()
    }

    @Before fun setUp() {
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(
                context, androidx.work.Configuration.Builder().setExecutor { it.run() }.build(),
            )
        }
        config.setEnabled(true)
        config.recordConsent()
        config.setShipToSiem(true)
        config.setSiemEndpoint("https://siem-a.example/ingest")
    }

    @After fun tearDown() {
        config.setShipToSiem(false)
        config.setEnabled(false)
        config.clearConsent()
        config.setSiemEndpoint("")
    }

    private fun payload(body: String = "ok") = ParsedPayload(
        raw = ByteArray(0), protocol = Protocol.HTTP1, direction = "OUTBOUND", method = "GET",
        urlPath = "/a", headers = emptyMap(), body = body, bodyEncoding = "utf-8",
        sizeBytes = body.length, piiRedacted = true, timestamp = 1L,
    )

    private fun queue(n: Int = 1, body: String = "ok") = repeat(n) {
        shipper.enqueue(payload(body), "s$it", "93.184.216.34", 443, "com.example", "example.com")
    }

    private fun waitFor(ms: Long = 5_000, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (done()) return true; Thread.sleep(20) }
        return done()
    }

    @Test fun control_events_are_sent_to_their_endpoint() {
        queue(3)
        shipper.shipNow()
        assertTrue(waitFor { sent.size == 1 })
        assertEquals("siem-a.example", sent[0].first.host)
        assertEquals(0, shipper.pendingCount())
    }

    @Test fun consent_withdrawn_after_queueing_sends_nothing_and_drops_the_queue() {
        queue(3)
        config.clearConsent()
        shipper.shipNow()
        Thread.sleep(300)
        assertTrue("sent without consent", sent.isEmpty())
        assertEquals(0, shipper.pendingCount())
    }

    @Test fun inspection_turned_off_after_queueing_sends_nothing_and_drops_the_queue() {
        queue(3)
        config.setEnabled(false)
        shipper.shipNow()
        Thread.sleep(300)
        assertTrue("sent with inspection off", sent.isEmpty())
        assertEquals(0, shipper.pendingCount())
    }

    @Test fun events_never_go_to_an_endpoint_set_after_they_were_queued() {
        queue(3)
        config.setSiemEndpoint("https://siem-b.example/ingest")
        shipper.shipNow()
        Thread.sleep(300)
        assertTrue("sent to another endpoint: ${sent.map { it.first }}", sent.isEmpty())
        assertEquals(0, shipper.pendingCount())
    }

    @Test fun the_queue_is_bounded_in_bytes() {
        config.setSiemEndpoint("")   // nothing is sent; everything stays queued
        val big = "x".repeat(512 * 1024)
        queue(100, big)
        assertTrue("queued ${shipper.pendingBytes()} bytes", shipper.pendingBytes() <= PayloadShipper.MAX_QUEUE_BYTES)
        assertTrue(shipper.pendingCount() < 100)
        queue(5)   // small events still fit once the big ones stop
        assertTrue(shipper.pendingBytes() <= PayloadShipper.MAX_QUEUE_BYTES)
    }

    @Test fun failed_uploads_back_off_exponentially() {
        succeed.set(false)
        queue(1)
        shipper.shipNow()
        assertTrue(waitFor { sent.size == 1 && !shipper.isShipping() })
        val first = shipper.nextAttemptAt() - now.get()
        assertTrue("first backoff $first ms", first >= PayloadShipper.MIN_BACKOFF_MS)

        // Automatic attempts before the backoff ends do nothing.
        shipper.shipIfDue()
        Thread.sleep(200)
        assertEquals(1, sent.size)

        now.addAndGet(first)
        shipper.shipIfDue()
        assertTrue(waitFor { sent.size == 2 && !shipper.isShipping() })
        val second = shipper.nextAttemptAt() - now.get()
        assertEquals(first * 2, second)

        // Success resets it.
        succeed.set(true)
        now.addAndGet(second)
        shipper.shipIfDue()
        assertTrue(waitFor { sent.size == 3 && !shipper.isShipping() })
        assertEquals(0L, shipper.nextAttemptAt())
        assertEquals(0, shipper.pendingCount())
    }

    @Test fun backoff_is_capped() {
        succeed.set(false)
        queue(1)
        repeat(20) {
            now.set(maxOf(now.get(), shipper.nextAttemptAt()))
            shipper.shipIfDue()
            assertTrue(waitFor { !shipper.isShipping() && sent.size == it + 1 })
        }
        assertTrue(shipper.nextAttemptAt() - now.get() <= PayloadShipper.MAX_BACKOFF_MS)
    }
}
