package com.privacyguard.app.core.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DomainClassifierTest {

    private val instagram = "com.instagram.android"
    private val facebook = "com.facebook.katana"
    private val game = "com.supercell.clashofclans"

    @Test
    fun `instagram api is first party for instagram`() {
        val c = DomainClassifier.classify("i.instagram.com", instagram)
        assertEquals(DomainPurpose.FIRST_PARTY, c.purpose)
        assertEquals("Meta Platforms", c.company)
    }

    @Test
    fun `graph facebook is first party inside meta apps but the facebook sdk elsewhere`() {
        assertEquals(DomainPurpose.FIRST_PARTY, DomainClassifier.classify("graph.facebook.com", instagram).purpose)
        assertEquals(DomainPurpose.FIRST_PARTY, DomainClassifier.classify("graph.facebook.com", facebook).purpose)
        val inGame = DomainClassifier.classify("graph.facebook.com", game)
        assertEquals(DomainPurpose.TRACKING, inGame.purpose)
        assertEquals("Facebook SDK", inGame.trackerName)
    }

    @Test
    fun `meta cdn is cdn not ads`() {
        val c = DomainClassifier.classify("scontent-ams2-1.cdninstagram.com", instagram)
        assertEquals(DomainPurpose.CDN, c.purpose)
        assertEquals("Meta Platforms", c.company)
        assertEquals(DomainPurpose.CDN, DomainClassifier.classify("static.xx.fbcdn.net", facebook).purpose)
    }

    @Test
    fun `audience network is ads in any app`() {
        assertEquals(DomainPurpose.ADS, DomainClassifier.classify("an.facebook.com", facebook).purpose)
        assertEquals(DomainPurpose.ADS, DomainClassifier.classify("an.facebook.com", game).purpose)
    }

    @Test
    fun `analytics sdks are analytics`() {
        val c = DomainClassifier.classify("inapps.appsflyersdk.com", game)
        assertEquals(DomainPurpose.ANALYTICS, c.purpose)
        assertEquals(DomainPurpose.ANALYTICS, DomainClassifier.classify("app-measurement.com", game).purpose)
        assertEquals(DomainPurpose.ADS, DomainClassifier.classify("googleads.g.doubleclick.net", game).purpose)
    }

    @Test
    fun `google service used by another app is a service`() {
        val c = DomainClassifier.classify("firebaseinstallations.googleapis.com", game)
        assertEquals(DomainPurpose.SERVICE, c.purpose)
        assertEquals("Google", c.company)
        assertEquals(DomainPurpose.FIRST_PARTY, DomainClassifier.classify("www.google.de", "com.google.android.googlequicksearchbox").purpose)
    }

    @Test
    fun `game maker domain is first party by name`() {
        val c = DomainClassifier.classify("game.clashofclans.com", game)
        assertEquals(DomainPurpose.FIRST_PARTY, c.purpose)
        assertEquals(DomainPurpose.FIRST_PARTY, DomainClassifier.classify("api.supercell.com", game).purpose)
    }

    @Test
    fun `third party cdn and unknown hosts`() {
        val cf = DomainClassifier.classify("d1234.cloudfront.net", game)
        assertEquals(DomainPurpose.CDN, cf.purpose)
        assertEquals("Amazon CloudFront", cf.company)
        val unknown = DomainClassifier.classify("api.example-weather.io", game)
        assertEquals(DomainPurpose.UNKNOWN, unknown.purpose)
        assertNull(unknown.company)
    }

    @Test
    fun `samsung system app talking to samsung`() {
        val c = DomainClassifier.classify("dir-apis.samsungdm.com", "com.wssyncmldm")
        assertEquals(DomainPurpose.FIRST_PARTY, c.purpose)
        assertEquals("Samsung", c.company)
    }

    @Test
    fun `base domain handles two label suffixes`() {
        assertEquals("bbc.co.uk", DomainClassifier.baseDomain("www.bbc.co.uk"))
        assertEquals("instagram.com", DomainClassifier.baseDomain("I.Instagram.com."))
        assertEquals("example.com", DomainClassifier.baseDomain("example.com"))
    }
}
