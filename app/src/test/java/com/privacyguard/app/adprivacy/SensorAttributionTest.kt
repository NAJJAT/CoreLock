package com.privacyguard.app.adprivacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorAttributionTest {

    private val own = "com.privacyguard.app"

    @Test
    fun screen_on_names_the_app_on_screen() {
        assertEquals("com.whatsapp", SensorUsageWatcher.likelyUser(true, "com.whatsapp", own))
    }

    @Test
    fun screen_off_names_no_app() {
        // The phone was in a pocket; WhatsApp was merely the last app opened.
        assertNull(SensorUsageWatcher.likelyUser(false, "com.whatsapp", own))
    }

    @Test
    fun old_rows_from_screen_off_lose_their_guess_on_screen() {
        val e = com.privacyguard.app.data.db.SensorEventEntity(sensor = "MIC", startedAt = 1, endedAt = null,
            screenOnAtStart = false, likelyPackage = "com.whatsapp", detail = null)
        assertNull(e.withoutScreenOffGuess().likelyPackage)
        assertEquals("com.whatsapp", e.copy(screenOnAtStart = true).withoutScreenOffGuess().likelyPackage)
    }

    @Test
    fun privacyguard_itself_is_never_named() {
        assertNull(SensorUsageWatcher.likelyUser(true, own, own))
    }
}
