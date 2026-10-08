package com.privacyguard.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * One camera or microphone use, as Android's system signals reported it, with
 * who (and how sure), the circumstances, and the alert decision. Kept 30 days,
 * on this device only. Keep in sync with AppDatabase.MIGRATION_8_9.
 */
@Entity(
    tableName = "sensor_events",
    indices = [Index("startTime"), Index("packageName", "startTime")],
)
data class SensorEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** CAMERA / MIC */
    val sensor: String,
    val startTime: Long,
    val endTime: Long?,
    /** Camera ids or audio source as reported, e.g. "camera 0,1" or "VOICE_COMMUNICATION". */
    val source: String,
    /** Null when the app could not be identified. */
    val packageName: String?,
    /** CONFIRMED / LIKELY / UNKNOWN — never shown as fact unless CONFIRMED. */
    val confidence: String,
    /** How the app was identified, e.g. "system app-ops record". */
    val attributionMethod: String,
    /** Comma-separated apps that could have done it, when not confirmed. */
    val candidates: String = "",
    val screenOn: Boolean,
    val locked: Boolean,
    val quietHours: Boolean,
    val inCall: Boolean,
    val openedRecently: Boolean,
    /** Bytes uploaded within ±60 s, null until measured. */
    val uploadBytes: Long? = null,
    val networkBurst: Boolean = false,
    /** NONE / LOW / HIGH / CRITICAL */
    val severity: String = "NONE",
    /** One sentence for the user, e.g. "The camera was used while your screen was off." */
    val reason: String = "",
    /** The user said "It was me". */
    @ColumnInfo(defaultValue = "0")
    val userMarkedExpected: Boolean = false,
)

@Dao
interface SensorEventDao {
    @Insert
    suspend fun insert(event: SensorEventEntity): Long

    @Update
    suspend fun update(event: SensorEventEntity)

    @Query("SELECT * FROM sensor_events WHERE id = :id")
    suspend fun byId(id: Long): SensorEventEntity?

    @Query("SELECT * FROM sensor_events WHERE startTime >= :since ORDER BY startTime DESC")
    fun observeSince(since: Long): Flow<List<SensorEventEntity>>

    @Query("SELECT * FROM sensor_events WHERE startTime >= :since ORDER BY startTime DESC")
    suspend fun since(since: Long): List<SensorEventEntity>

    @Query("SELECT * FROM sensor_events WHERE packageName = :packageName AND startTime >= :since ORDER BY startTime DESC")
    suspend fun forPackage(packageName: String, since: Long): List<SensorEventEntity>

    @Query("UPDATE sensor_events SET userMarkedExpected = 1 WHERE id = :id")
    suspend fun markExpected(id: Long)

    /** Uses of [sensor] the user confirmed for [packageName]: the app's learned baseline. */
    @Query("SELECT COUNT(*) FROM sensor_events WHERE packageName = :packageName AND sensor = :sensor AND userMarkedExpected = 1")
    suspend fun expectedCount(packageName: String, sensor: String): Int

    @Query("DELETE FROM sensor_events WHERE startTime < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}
