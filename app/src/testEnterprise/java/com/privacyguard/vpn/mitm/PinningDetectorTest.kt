package com.privacyguard.vpn.mitm

import com.privacyguard.vpn.mitm.PinningDetector.Rejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinningDetectorTest {

    private class MemoryStore : PinningDetector.Store {
        var domains = emptySet<String>()
        var packages = emptySet<String>()
        override fun load() = domains to packages
        override fun save(domains: Set<String>, packages: Set<String>) {
            this.domains = domains
            this.packages = packages
        }
    }

    @Test
    fun anAppRejectingTheCaIsPassedThroughForEveryHost() {
        val pins = PinningDetector()
        pins.recordRejection("com.zhiliaoapp.musically", "v45.tiktokcdn-eu.com", Rejection.ALERT)
        assertTrue(pins.isPinned("com.zhiliaoapp.musically", "api16-normal.tiktokv.eu"))
        assertFalse(pins.isPinned("com.example.other", "api16-normal.tiktokv.eu"))
    }

    @Test
    fun aBrowserRejectionPinsOnlyThatSite() {
        val pins = PinningDetector()
        pins.recordRejection("com.android.chrome", "pinned.example", Rejection.ALERT)
        assertTrue(pins.isPinned("com.android.chrome", "pinned.example"))
        assertFalse(pins.isPinned("com.android.chrome", "www.bbc.com"))
    }

    @Test
    fun oneSilentCloseIsNotTreatedAsPinning() {
        val pins = PinningDetector()
        pins.recordRejection("com.android.chrome", "www.bbc.com", Rejection.CLOSED)
        assertFalse(pins.isPinned("com.android.chrome", "www.bbc.com"))
        pins.recordSuccess("www.bbc.com")
        pins.recordRejection("com.android.chrome", "www.bbc.com", Rejection.CLOSED)
        assertFalse(pins.isPinned("com.android.chrome", "www.bbc.com"))
        pins.recordRejection("com.android.chrome", "www.bbc.com", Rejection.CLOSED)
        assertTrue(pins.isPinned("com.android.chrome", "www.bbc.com"))
    }

    @Test
    fun learnedPinsSurviveARestart() {
        val store = MemoryStore()
        PinningDetector(store).apply {
            recordRejection("com.snapchat.android", "app.snapchat.com", Rejection.ALERT)
            recordRejection(null, "broken-upstream.example", Rejection.UPSTREAM)
        }
        assertEquals(setOf("com.snapchat.android"), store.packages)
        val restarted = PinningDetector(store)
        assertTrue(restarted.isPinned("com.snapchat.android", "cf-st.sc-cdn.net"))
        // A server-side failure is not learned: it is retried after a restart.
        assertFalse(restarted.shouldPassThrough(null, "broken-upstream.example"))
    }

    @Test
    fun aServerFailureIsNotTheAppRejectingTheCertificate() {
        val store = MemoryStore()
        val pins = PinningDetector(store)
        pins.recordRejection("com.android.chrome", "broken-upstream.example", Rejection.UPSTREAM)
        assertFalse(pins.isPinned("com.android.chrome", "broken-upstream.example"))
        assertTrue(pins.shouldPassThrough("com.android.chrome", "broken-upstream.example"))
        assertTrue(store.domains.isEmpty() && store.packages.isEmpty())
        // A later successful interception clears it.
        pins.recordSuccess("broken-upstream.example")
        assertFalse(pins.shouldPassThrough("com.android.chrome", "broken-upstream.example"))
    }

    @Test
    fun aLocalFailureNeverMarksTheAppOrSite() {
        val pins = PinningDetector()
        pins.skipForNow("www.bbc.com")
        assertFalse(pins.isPinned("com.android.chrome", "www.bbc.com"))
        assertTrue(pins.shouldPassThrough("com.android.chrome", "www.bbc.com"))
        assertFalse(pins.shouldPassThrough("com.android.chrome", "www.example.org"))
    }
}
