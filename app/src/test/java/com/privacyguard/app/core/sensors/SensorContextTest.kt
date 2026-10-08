package com.privacyguard.app.core.sensors

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SensorContextTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(hour: Int, minute: Int = 0): Long = Calendar.getInstance(utc).apply {
        clear(); set(2026, Calendar.OCTOBER, 8, hour, minute)
    }.timeInMillis

    @After
    fun tearDown() = UploadMeter.clear()

    @Test
    fun quietHoursWrapPastMidnight() {
        val q = QuietHours(23 * 60, 7 * 60)
        assertTrue(q.contains(at(2), utc))
        assertTrue(q.contains(at(23, 30), utc))
        assertFalse(q.contains(at(7), utc))
        assertFalse(q.contains(at(14), utc))
    }

    @Test
    fun quietHoursWithinOneDay() {
        val q = QuietHours(13 * 60, 14 * 60)
        assertTrue(q.contains(at(13, 30), utc))
        assertFalse(q.contains(at(14, 1), utc))
    }

    @Test
    fun uploadMeterCountsOnlyTheWindow() {
        val t = at(2)
        UploadMeter.record(10_100, 300_000, t - 120_000)   // before the window
        UploadMeter.record(10_100, 400_000, t + 10_000)
        UploadMeter.record(10_200, 999_999, t + 10_000)    // other app
        assertEquals(400_000L, UploadMeter.bytesBetween(10_100, t - 60_000, t + 60_000))
    }

    private class FakeDevice(
        var screenOn: Boolean = true, var locked: Boolean = false,
        var inCall: Boolean = false, var screenOnAt: Long? = null,
    ) : DeviceState {
        override fun isScreenOn() = screenOn
        override fun isLocked() = locked
        override fun isInCall() = inCall
        override fun lastScreenOnAt() = screenOnAt
    }

    private fun builder(device: DeviceState, opened: Set<String> = emptySet(), uploads: Map<Int, Long> = emptyMap()) =
        SensorContextBuilder(
            device = device,
            quietHours = { QuietHours(23 * 60, 7 * 60) },
            openedSince = { _, _ -> opened },
            uidOf = mapOf("com.whatsapp" to 1, "com.example.spy" to 2)::get,
            uploads = { uid, _, _ -> uploads[uid] ?: 0 },
            zone = { utc },
        )

    @Test
    fun capturesScreenOffAtNight() {
        val use = SensorUse(1, SensorType.MIC, at(2), null, "MIC")
        val ctx = builder(FakeDevice(screenOn = false, locked = true))
            .atStart(use, Attribution(null, Confidence.UNKNOWN, "test"))
        assertFalse(ctx.screenOn)
        assertTrue(ctx.locked)
        assertTrue(ctx.quietHours)
        assertEquals(2, ctx.hourOfDay)
        assertFalse(ctx.openedRecently)
        assertNull(ctx.uploadBytes)
    }

    @Test
    fun openedRecentlyAppliesToTheAttributedApp() {
        val use = SensorUse(1, SensorType.CAMERA, at(14), null, "camera 0")
        val ctx = builder(FakeDevice(screenOnAt = at(14) - 1_000), opened = setOf("com.whatsapp"))
            .atStart(use, Attribution("com.whatsapp", Confidence.LIKELY, "test"))
        assertTrue(ctx.openedRecently)
        assertEquals(1_000L, ctx.sinceScreenOnMs)
    }

    @Test
    fun uploadsPickTheBusiestCandidateWhenUnknown() {
        val use = SensorUse(1, SensorType.CAMERA, at(2), at(2) + 5_000, "camera 0")
        val b = builder(FakeDevice(), uploads = mapOf(1 to 10_000L, 2 to 2_000_000L))
        val ctx = b.withUploads(
            b.atStart(use, Attribution(null, Confidence.UNKNOWN, "t")), use,
            Attribution(null, Confidence.UNKNOWN, "t", candidates = listOf("com.whatsapp", "com.example.spy")),
        )
        assertEquals("com.example.spy", ctx.uploadPackage)
        assertTrue(ctx.networkBurst)
    }
}
