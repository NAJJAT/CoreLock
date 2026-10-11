package com.privacyguard.core.tls

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class TlsBaselineEngineTest {

    private class MemoryStore : TlsBaselineStore {
        val profiles = mutableMapOf<Pair<Int, String>, TlsAppProfile>()
        val fingerprints = mutableListOf<KnownFingerprint>()
        override suspend fun profile(uid: Int, packageName: String) = profiles[uid to packageName]
        override suspend fun upsertProfile(profile: TlsAppProfile) { profiles[profile.uid to profile.packageName] = profile }
        override suspend fun isKnown(uid: Int, packageName: String, ja4: String) =
            fingerprints.any { it.uid == uid && it.packageName == packageName && it.ja4 == ja4 }
        override suspend fun insertFingerprint(fp: KnownFingerprint) { fingerprints += fp }
        override suspend fun touch(uid: Int, packageName: String, ja4: String, now: Long) = Unit
    }

    private val day = TimeUnit.DAYS.toMillis(1)
    private var now = 1_700_000_000_000L
    private val store = MemoryStore()
    private val engine = TlsBaselineEngine(store, clock = { now })

    private val uid = 10_123
    private val pkg = "com.privacyguard.testapp"
    private val v1 = AppEnvironment(1, "1|os")
    private val v2 = AppEnvironment(2, "2|os")

    // Shapes of the two clients the device test uses: OkHttp on Conscrypt
    // (TLS 1.3, h2) and BouncyCastle JSSE (different ciphers/extensions, no ALPN).
    private val okHttpHello = ClientHello(
        tlsVersion = 0x0303,
        cipherSuites = listOf(0x1301, 0x1302, 0x1303, 0xc02b, 0xc02c, 0xcca9, 0xc02f, 0xc030, 0xcca8, 0xc013, 0xc014, 0x009c, 0x009d, 0x002f, 0x0035),
        compressionMethods = listOf(0),
        extensions = listOf(0x0000, 0x0017, 0xff01, 0x000a, 0x000b, 0x0023, 0x0010, 0x0005, 0x000d, 0x0033, 0x002d, 0x002b, 0x0015),
        supportedGroups = listOf(0x001d, 0x0017, 0x0018),
        ecPointFormats = listOf(0),
        sni = "www.example.com",
        alpn = listOf("h2", "http/1.1"),
        supportedVersions = listOf(0x0304, 0x0303),
        signatureAlgorithms = listOf(0x0403, 0x0804, 0x0401, 0x0503, 0x0805, 0x0501, 0x0806, 0x0601, 0x0201),
    )
    private val bouncyCastleHello = ClientHello(
        tlsVersion = 0x0303,
        cipherSuites = listOf(0x1301, 0x1303, 0x1302, 0xc02c, 0xc02b, 0xcca9, 0xc030, 0xc02f, 0xcca8, 0x009f, 0x009e, 0xccaa, 0xc024, 0xc023, 0xc028, 0xc027, 0x00ff),
        compressionMethods = listOf(0),
        extensions = listOf(0x000a, 0x000b, 0x0016, 0x0017, 0x000d, 0x0032, 0x002b, 0x002d, 0x0033, 0x0000),
        supportedGroups = listOf(0x001d, 0x001e, 0x0017, 0x0018, 0x0019, 0x0100, 0x0101),
        ecPointFormats = listOf(0),
        sni = "www.example.com",
        alpn = emptyList(),
        supportedVersions = listOf(0x0304, 0x0303),
        signatureAlgorithms = listOf(0x0807, 0x0808, 0x0403, 0x0503, 0x0603, 0x0804, 0x0805, 0x0806),
    )

    private suspend fun observe(hello: ClientHello, env: AppEnvironment = v1) =
        engine.observe(uid, pkg, Ja4Fingerprinter.ja4(hello), hello.ja3Hash(), hello.sni, env)

    private fun alerts() = store.fingerprints.count { it.origin == FingerprintOrigin.ALERTED }

    @Test
    fun okhttp_then_second_library_gives_two_fingerprints_and_one_alert() = runBlocking {
        assertNotEquals(Ja4Fingerprinter.ja4(okHttpHello), Ja4Fingerprinter.ja4(bouncyCastleHello))

        assertEquals(BaselineVerdict.Learned(FingerprintOrigin.BASELINE), observe(okHttpHello))
        now += 8 * day // learning over
        assertEquals(BaselineVerdict.Known, observe(okHttpHello))
        val verdict = observe(bouncyCastleHello)

        assertTrue(verdict is BaselineVerdict.Alert)
        assertEquals(2, store.fingerprints.size)
        assertEquals(1, alerts())
    }

    @Test
    fun same_new_fingerprint_alerts_only_once() = runBlocking {
        observe(okHttpHello)
        now += 8 * day
        observe(bouncyCastleHello)
        assertEquals(BaselineVerdict.Known, observe(bouncyCastleHello))
        assertEquals(1, alerts())
    }

    @Test
    fun new_fingerprint_after_app_update_is_accepted_silently() = runBlocking {
        observe(okHttpHello)
        now += 8 * day
        // Simulated update: versionCode 1 -> 2.
        val verdict = observe(bouncyCastleHello, env = v2)

        assertEquals(BaselineVerdict.Learned(FingerprintOrigin.AFTER_UPDATE), verdict)
        assertEquals(2, store.fingerprints.size)
        assertEquals(0, alerts())
    }

    @Test
    fun update_grace_expires_after_24_hours() = runBlocking {
        observe(okHttpHello)
        now += 8 * day
        observe(okHttpHello, env = v2) // update detected here
        now += 2 * day
        assertTrue(observe(bouncyCastleHello, env = v2) is BaselineVerdict.Alert)
    }

    @Test
    fun anything_new_during_the_first_seven_days_is_learned() = runBlocking {
        observe(okHttpHello)
        now += 6 * day
        assertEquals(BaselineVerdict.Learned(FingerprintOrigin.BASELINE), observe(bouncyCastleHello))
        assertEquals(0, alerts())
    }

    @Test
    fun baselines_are_per_app() = runBlocking {
        observe(okHttpHello)
        now += 8 * day
        // A different app's first sighting starts its own learning period.
        val other = engine.observe(uid + 1, "com.other", Ja4Fingerprinter.ja4(bouncyCastleHello), "x", null, v1)
        assertEquals(BaselineVerdict.Learned(FingerprintOrigin.BASELINE), other)
    }

    @Test
    fun parallel_connections_with_same_new_fingerprint_raise_one_alert() = runBlocking {
        observe(okHttpHello)
        now += 8 * day
        val verdicts = (1..8).map { async { observe(bouncyCastleHello) } }.awaitAll()
        assertEquals(1, verdicts.count { it is BaselineVerdict.Alert })
        assertEquals(1, alerts())
    }
}
