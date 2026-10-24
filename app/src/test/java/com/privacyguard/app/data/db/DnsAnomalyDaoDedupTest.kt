package com.privacyguard.app.data.db

import androidx.room.Room
import com.privacyguard.app.data.repository.DnsAnomalyRepo
import com.privacyguard.vpn.inspector.DnsAnomalyDetector
import com.privacyguard.vpn.inspector.DnsAnomalyDetector.AnomalyType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The detector's cooldown is in memory; the database repeats it so a VPN restart does
 * not store the same alert again. A burst counts once per app, so a browser that loads
 * a page with dozens of ad hosts adds one alert to the bell, not dozens.
 */
@RunWith(RobolectricTestRunner::class)
class DnsAnomalyDaoDedupTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: DnsAnomalyRepo
    private val now = 1_800_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = DnsAnomalyRepo(db.dnsAnomalyDao())
    }

    @After
    fun tearDown() = db.close()

    private fun anomaly(pkg: String, domain: String, type: AnomalyType, at: Long = now) =
        DnsAnomalyDetector.Anomaly(at, pkg, domain, type, "test", severity = 4)

    @Test
    fun a_burst_is_stored_once_per_app_per_day() = runBlocking {
        val stored = (1..60).count { repo.save(anomaly("com.android.chrome", "ad$it.example.net", AnomalyType.HIGH_FREQUENCY, now + it)) }
        assertEquals(1, stored)
        // Another app, and the same app a day later, each get their own.
        assertEquals(true, repo.save(anomaly("com.news", "n1.example.org", AnomalyType.HIGH_FREQUENCY)))
        val nextDay = now + TlsAlertDao.ALERT_DEDUP_MS + 1_000
        assertEquals(true, repo.save(anomaly("com.android.chrome", "ad1.example.net", AnomalyType.HIGH_FREQUENCY, nextDay)))
        assertEquals(3, db.dnsAnomalyDao().since(0).size)
    }

    @Test
    fun other_findings_stay_once_per_app_and_host() = runBlocking {
        assertEquals(true, repo.save(anomaly("com.app", "x1.example.com", AnomalyType.DGA_BEACON)))
        assertEquals(true, repo.save(anomaly("com.app", "x2.example.com", AnomalyType.DGA_BEACON)))
        assertEquals(false, repo.save(anomaly("com.app", "x1.example.com", AnomalyType.DGA_BEACON, now + 1)))
        // A stored burst does not stop a beacon finding on another host of the same app.
        repo.save(anomaly("com.app", "b1.example.com", AnomalyType.HIGH_FREQUENCY))
        assertEquals(true, repo.save(anomaly("com.app", "x3.example.com", AnomalyType.DGA_BEACON)))
    }
}
