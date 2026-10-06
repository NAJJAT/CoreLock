package com.privacyguard.vpn.mitm

import com.privacyguard.vpn.mitm.LeakDetector.LeakType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LeakDetectorTest {

    private fun detect(text: String) = LeakDetector.detect(text)

    @Test
    fun `location in json query and pair form`() {
        assertEquals(setOf(LeakType.LOCATION), detect("""{"latitude": 52.370216, "longitude": 4.895168}"""))
        assertEquals(setOf(LeakType.LOCATION), detect("/v1/ads?lat=52.3702&lon=4.8951&app=x"))
        assertEquals(setOf(LeakType.LOCATION), detect("/search?ll=52.3702,4.8951"))
        assertEquals(setOf(LeakType.LOCATION), detect("location=52.3702%2C4.8951"))
    }

    @Test
    fun `numbers without location keys are ignored`() {
        assertTrue(detect("""{"price": 52.370216, "version": "4.8951", "flat": 1}""").isEmpty())
        assertTrue(detect("""{"lat": 52}""").isEmpty())   // too coarse to be a coordinate
    }

    @Test
    fun `advertising and device identifiers`() {
        assertEquals(setOf(LeakType.ADVERTISING_ID), detect("""{"gaid":"38400000-8cf0-11bd-b23e-10b96e40000d"}"""))
        assertEquals(setOf(LeakType.ADVERTISING_ID), detect("advertising_id=38400000-8cf0-11bd-b23e-10b96e40000d"))
        assertEquals(setOf(LeakType.DEVICE_ID), detect("""{"android_id":"9774d56d682e549c"}"""))
        assertEquals(setOf(LeakType.DEVICE_ID), detect("X-Device-Id: 7f3a9c21b8e44d10"))
    }

    @Test
    fun `network info`() {
        assertEquals(setOf(LeakType.NETWORK_INFO), detect("""{"bssid":"a4:2b:b0:11:22:33"}"""))
        assertEquals(setOf(LeakType.NETWORK_INFO), detect("ssid=HomeWifi&x=1"))
        assertEquals(setOf(LeakType.NETWORK_INFO), detect("""{"mcc":"204","mnc":"08"}"""))
    }

    @Test
    fun `email and phone`() {
        assertEquals(setOf(LeakType.EMAIL), detect("user=someone%40example.com"))
        assertTrue(detect("/img/icon@2x.png").isEmpty())
        assertEquals(setOf(LeakType.PHONE_NUMBER), detect("""{"phone":"+31 6 12345678"}"""))
        assertTrue(detect("""{"hotel": "12345678"}""").isEmpty())
    }

    @Test
    fun `combined detection and encoding round trip`() {
        val leaks = LeakDetector.detect(
            "/track?lat=52.3702&lng=4.8951",
            mapOf("X-Ad-Id" to "38400000-8cf0-11bd-b23e-10b96e40000d"),
            """{"email":"me@example.org"}""",
        )
        assertEquals(setOf(LeakType.LOCATION, LeakType.ADVERTISING_ID, LeakType.EMAIL), leaks)
        assertEquals(leaks, LeakDetector.decode(LeakDetector.encode(leaks)))
        assertTrue(LeakDetector.decode(null).isEmpty())
        assertTrue(LeakDetector.decode("BOGUS").isEmpty())
    }
}
