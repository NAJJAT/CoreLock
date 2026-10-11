package com.privacyguard.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One period during which the microphone or a camera was in use by some app.
 *
 * Android does not tell regular apps which app is recording, so [likelyPackage]
 * is the app that was on screen when use started (null when unknown, e.g. the
 * screen was off or Usage Access is not granted).
 */
@Entity(tableName = "sensor_events", indices = [Index("startedAt")])
data class SensorEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sensor: String,            // SENSOR_MIC | SENSOR_CAMERA
    val startedAt: Long,
    // Null while in use. Watching stops with the VPN, which ends open rows at that
    // time; a row still null after that means the end was not recorded (crash).
    val endedAt: Long?,
    val screenOnAtStart: Boolean,
    val likelyPackage: String?,
    val detail: String?,           // camera id, or audio source name
) {
    companion object {
        const val SENSOR_MIC = "MIC"
        const val SENSOR_CAMERA = "CAMERA"
    }
}

@Dao
interface SensorEventDao {
    @Insert
    suspend fun insert(event: SensorEventEntity): Long

    @Query("UPDATE sensor_events SET endedAt = :endedAt WHERE id = :id")
    suspend fun end(id: Long, endedAt: Long)

    @Query("UPDATE sensor_events SET likelyPackage = :pkg WHERE id = :id")
    suspend fun setLikelyPackage(id: Long, pkg: String?)

    @Query("SELECT * FROM sensor_events ORDER BY startedAt DESC LIMIT :limit")
    fun recentFlow(limit: Int): Flow<List<SensorEventEntity>>

    /** Every use that overlaps [from, to], including ones still running. */
    @Query("""
        SELECT * FROM sensor_events
        WHERE sensor = :sensor AND startedAt <= :to AND (endedAt IS NULL OR endedAt >= :from)
        ORDER BY startedAt
    """)
    suspend fun overlapping(sensor: String, from: Long, to: Long): List<SensorEventEntity>

    @Query("DELETE FROM sensor_events WHERE startedAt < :cutoff")
    suspend fun pruneOld(cutoff: Long)
}
