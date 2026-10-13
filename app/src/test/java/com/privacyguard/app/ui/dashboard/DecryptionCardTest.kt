package com.privacyguard.app.ui.dashboard

import com.privacyguard.app.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** #5: the card follows the real inspection state and never claims "no decryption" while decrypting. */
class DecryptionCardTest {

    @Test
    fun inspection_on_says_decrypting_as_a_warning() {
        val c = DecryptionCard.of(inspectionActive = true)
        assertEquals(R.string.dash_card_decrypt_on, c.title)
        assertEquals(R.string.dash_status_decrypting, c.status)
        assertEquals(CardSeverity.WARNING, c.severity)
    }

    @Test
    fun inspection_off_says_no_decryption() {
        val c = DecryptionCard.of(inspectionActive = false)
        assertEquals(R.string.dash_card_no_decrypt, c.title)
        assertEquals(R.string.dash_status_off, c.status)
        assertEquals(CardSeverity.GOOD, c.severity)
    }
}
