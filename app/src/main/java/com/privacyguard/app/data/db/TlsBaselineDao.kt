package com.privacyguard.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TlsBaselineDao {

    @Query("SELECT * FROM tls_app_profiles WHERE uid = :uid AND packageName = :pkg")
    suspend fun profile(uid: Int, pkg: String): TlsAppProfileEntity?

    @Upsert
    suspend fun upsertProfile(profile: TlsAppProfileEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM tls_fingerprints WHERE uid = :uid AND packageName = :pkg AND ja4 = :ja4)")
    suspend fun isKnown(uid: Int, pkg: String, ja4: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFingerprint(fp: TlsFingerprintEntity)

    @Query("""
        UPDATE tls_fingerprints SET lastSeen = :now, seenCount = seenCount + 1
        WHERE uid = :uid AND packageName = :pkg AND ja4 = :ja4
    """)
    suspend fun touch(uid: Int, pkg: String, ja4: String, now: Long)

    @Query("UPDATE tls_fingerprints SET origin = 'USER_TRUSTED' WHERE uid = :uid AND packageName = :pkg AND ja4 = :ja4")
    suspend fun markTrusted(uid: Int, pkg: String, ja4: String)

    @Query("SELECT * FROM tls_app_profiles WHERE packageName = :pkg")
    suspend fun profilesFor(pkg: String): List<TlsAppProfileEntity>

    @Query("SELECT * FROM tls_app_profiles")
    fun profilesFlow(): Flow<List<TlsAppProfileEntity>>

    @Query("SELECT * FROM tls_fingerprints ORDER BY lastSeen DESC")
    fun fingerprintsFlow(): Flow<List<TlsFingerprintEntity>>

    @Query("SELECT * FROM tls_fingerprints WHERE packageName = :pkg ORDER BY firstSeen")
    suspend fun fingerprintsFor(pkg: String): List<TlsFingerprintEntity>

    // ── Retention (PruneWorker, F-13): no schema change, DELETE only ──────

    @Query("DELETE FROM tls_fingerprints WHERE lastSeen < :cutoff")
    suspend fun deleteFingerprintsNotSeenSince(cutoff: Long)

    /** Keeps each app's [maxPerApp] most recently seen fingerprints (ties broken by id). */
    @Query("""
        DELETE FROM tls_fingerprints WHERE id IN (
            SELECT f.id FROM tls_fingerprints f WHERE (
                SELECT COUNT(*) FROM tls_fingerprints g
                WHERE g.uid = f.uid AND g.packageName = f.packageName
                  AND (g.lastSeen > f.lastSeen OR (g.lastSeen = f.lastSeen AND g.id > f.id))
            ) >= :maxPerApp
        )
    """)
    suspend fun trimFingerprintsPerApp(maxPerApp: Int)

    /** Keeps the [max] most recently seen fingerprints of all apps. */
    @Query("DELETE FROM tls_fingerprints WHERE id NOT IN (SELECT id FROM tls_fingerprints ORDER BY lastSeen DESC, id DESC LIMIT :max)")
    suspend fun capFingerprints(max: Int)
}
