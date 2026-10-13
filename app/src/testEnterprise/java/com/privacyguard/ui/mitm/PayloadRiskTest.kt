package com.privacyguard.ui.mitm

import com.privacyguard.vpn.mitm.LeakDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadRiskTest {

    private fun input(headers: String = "{}", pii: Boolean = false, method: String? = "GET", body: Int = 0, metadataOnly: Boolean = false) =
        PayloadRiskInput(method, headers, pii, body, metadataOnly)

    @Test
    fun a_header_value_mentioning_authorization_is_not_an_auth_header() {
        // The old rule searched the raw JSON, so any value containing the word counted.
        val s = PayloadRisk.score(input("""{"Referer":"https://x/authorization-help"}"""))
        assertEquals(0, s)
    }

    @Test
    fun auth_and_cookie_header_names_count_case_insensitively() {
        assertEquals(35, PayloadRisk.score(input("""{"Authorization":"Bearer x","Cookie":"a=b"}""")))
    }

    @Test
    fun a_personal_data_leak_flags_the_entry() {
        val headers = """{"${LeakDetector.LEAKS_HEADER}":"${LeakDetector.encode(setOf(LeakDetector.LeakType.LOCATION))}"}"""
        assertTrue(PayloadRisk.isFlagged(input(headers)))
        assertEquals(PayloadRisk.Bucket.FLAGGED, PayloadRisk.bucket(input(headers)))
    }

    @Test
    fun metadata_only_entries_are_their_own_bucket_and_never_flagged() {
        val m = input(pii = true, metadataOnly = true)
        assertFalse(PayloadRisk.isFlagged(m))
        assertEquals(PayloadRisk.Bucket.METADATA_ONLY, PayloadRisk.bucket(m))
    }

    @Test
    fun every_entry_is_in_exactly_one_bucket() {
        val all = listOf(
            input(), input(method = "POST", body = 600), input("""{"Authorization":"x"}"""),
            input(pii = true), input(metadataOnly = true),
        )
        val counts = all.groupingBy { PayloadRisk.bucket(it) }.eachCount()
        assertEquals(all.size, counts.values.sum())
    }
}
