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
    // time. A row the process died with (crash, app update) is set to
    // [END_NOT_RECORDED] when watching starts again: it is not still running.
    val endedAt: Long?,
    val screenOnAtStart: Boolean,
    val likelyPackage: String?,
    val detail: String?,           // camera id, or audio source name
) {
    companion object {
        const val SENSOR_MIC = "MIC"
        const val SENSOR_CAMERA = "CAMERA"
        /** [endedAt] of a use whose end was lost with the process. */
        const val END_NOT_RECORDED = -1L
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

    /**
     * Uses left open by a process that died: nothing watches them any more, so they
     * cannot be running. Call before watching starts.
     */
    @Query("UPDATE sensor_events SET endedAt = ${SensorEventEntity.END_NOT_RECORDED} WHERE endedAt IS NULL")
    suspend fun closeOrphans(): Int

    /**
     * Every use that overlaps [from, to], including ones still running. A use whose
     * end was not recorded counts only if it started inside the window: it may have
     * lasted seconds, so it says nothing about later hours.
     */
    @Query("""
        SELECT * FROM sensor_events
        WHERE sensor = :sensor AND startedAt <= :to AND (
            endedAt IS NULL
            OR (endedAt != ${SensorEventEntity.END_NOT_RECORDED} AND endedAt >= :from)
            OR (endedAt = ${SensorEventEntity.END_NOT_RECORDED} AND startedAt >= :from))
        ORDER BY startedAt
    """)
    suspend fun overlapping(sensor: String, from: Long, to: Long): List<SensorEventEntity>

    @Query("DELETE FROM sensor_events WHERE startedAt < :cutoff")
    suspend fun pruneOld(cutoff: Long)
}
