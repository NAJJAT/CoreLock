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
}
