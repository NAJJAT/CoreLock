package com.privacyguard.app.workers

import androidx.room.Room
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.TlsFingerprintEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The TLS fingerprint baseline has a retention rule (F-13): fingerprints not seen for
 * [PruneWorker.FINGERPRINT_RETENTION_DAYS] go, each app keeps at most
 * [PruneWorker.MAX_FINGERPRINTS_PER_APP] (the most recently seen), and the table at
 * most [PruneWorker.MAX_FINGERPRINTS].
 */
@RunWith(RobolectricTestRunner::class)
class TlsFingerprintRetentionTest {

    private val day = 86_400_000L
    private val now = 1_000L * day
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun count(where: String = "1"): Int =
        db.query("SELECT COUNT(*) FROM tls_fingerprints WHERE $where", null).use { it.moveToFirst(); it.getInt(0) }

    private suspend fun fp(pkg: String, ja4: String, lastSeen: Long, uid: Int = 10_000) =
        db.tlsBaselineDao().insertFingerprint(TlsFingerprintEntity(
            uid = uid, packageName = pkg, ja4 = ja4, ja3 = "x", firstSni = null, firstSeen = lastSeen,
            lastSeen = lastSeen, seenCount = 1, origin = "BASELINE", versionCode = 1,
        ))

    private suspend fun prune(maxPerApp: Int = PruneWorker.MAX_FINGERPRINTS_PER_APP, max: Int = PruneWorker.MAX_FINGERPRINTS) =
        PruneWorker.prune(db, now, retentionDays = 30, payloadCutoffMs = 0L,
            maxFingerprintsPerApp = maxPerApp, maxFingerprints = max)

    @Test fun fingerprints_not_seen_for_the_retention_period_are_deleted() = runBlocking {
        fp("a", "old", now - (PruneWorker.FINGERPRINT_RETENTION_DAYS + 1) * day)
        fp("a", "recent", now - 31 * day)   // older than history retention, still a baseline
        prune()
        assertEquals(1, count())
        assertEquals(1, count("ja4 = 'recent'"))
    }

    @Test fun each_app_keeps_only_its_most_recently_seen_fingerprints() = runBlocking {
        repeat(10) { fp("a", "a$it", now - it * day) }
        repeat(3) { fp("b", "b$it", now - it * day) }
        prune(maxPerApp = 4)
        assertEquals(4, count("packageName = 'a'"))
        assertEquals(1, count("packageName = 'a' AND ja4 = 'a0'"))
        assertEquals(0, count("packageName = 'a' AND ja4 = 'a4'"))
        assertEquals(3, count("packageName = 'b'"))
    }

    @Test fun the_whole_table_is_capped() = runBlocking {
        repeat(30) { fp("p$it", "f", now - it * 1_000L, uid = 10_000 + it) }
        prune(max = 20)
        assertEquals(20, count())
        assertTrue(count("packageName = 'p0'") == 1)
        assertEquals(0, count("packageName = 'p29'"))
    }
}
