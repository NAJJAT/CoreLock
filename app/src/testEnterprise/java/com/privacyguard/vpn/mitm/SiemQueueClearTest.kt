package com.privacyguard.vpn.mitm

import android.content.Context
import com.privacyguard.app.data.repository.LocalDataEraser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * N-2: "Clear all data" removes everything CoreLock recorded, in memory too. The SIEM queue
 * holds decrypted payloads waiting to be sent; after a clear none of them may be sent.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class SiemQueueClearTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val config = MitmConfig(context)

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

    private fun payload() = ParsedPayload(
        raw = ByteArray(0), protocol = Protocol.HTTP1, direction = "OUTBOUND", method = "GET",
        urlPath = "/a", headers = emptyMap(), body = "secret", bodyEncoding = "utf-8",
        sizeBytes = 6, piiRedacted = true, timestamp = 1L,
    )

    private fun PayloadShipper.queue(n: Int) = repeat(n) {
        enqueue(payload(), "s$it", "93.184.216.34", 443, "com.example", "example.com")
    }

    @Test fun clear_all_data_empties_the_shared_queue() {
        // A fresh process-wide shipper, as the sibling tests do: another test class may have
        // left its own (with another app's config) in the singleton.
        PayloadShipper::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        val shipper = PayloadShipper.getInstance(context)
        shipper.queue(3)
        assertEquals(3, shipper.pendingCount())

        LocalDataEraser.notifyListeners()

        assertEquals(0, shipper.pendingCount())
        assertEquals(0L, shipper.pendingBytes())
    }

    @Test fun a_batch_in_flight_during_a_clear_is_not_put_back_when_it_fails() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val shipper = PayloadShipper(config) { _, _, _ ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            false  // the upload fails: normally the batch goes back to the queue
        }
        shipper.queue(2)
        shipper.shipNow()
        assertTrue(started.await(5, TimeUnit.SECONDS))

        shipper.clearAll()
        release.countDown()

        val end = System.currentTimeMillis() + 5_000
        while (shipper.isShipping() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals("cleared events must not come back", 0, shipper.pendingCount())
    }
}
