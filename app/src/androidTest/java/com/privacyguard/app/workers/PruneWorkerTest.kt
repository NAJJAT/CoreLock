package com.privacyguard.app.workers

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.db.PayloadLogEntity
import com.privacyguard.app.data.db.SensorEventEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PruneWorkerTest {

    private val day = 86_400_000L
    private val now = 1_000L * day
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = db.close()

    private fun count(table: String): Int =
        db.query("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }

    private fun payload(timestamp: Long) = PayloadLogEntity(
        timestamp = timestamp, sessionId = "s", direction = "OUT", ownerPackage = "p",
        sniHostname = null, destinationIp = "1.1.1.1", destinationPort = 443, protocol = "TCP",
        method = null, urlPath = null, headers = "", body = null, bodyEncoding = "",
        sizeBytes = 0, piiRedacted = false, isMitmSuccess = true,
    )

    @Test
    fun prune_deletesHistoryPastRetentionAndKeepsRecent() = runBlocking {
        db.connectionDao().insert(ConnectionEntity(appUid = 1, timestamp = now - 31 * day))
        db.connectionDao().insert(ConnectionEntity(appUid = 1, timestamp = now - 29 * day))

        PruneWorker.prune(db, now, retentionDays = 30, payloadCutoffMs = 0L)

        assertEquals(1, count("connections"))
    }

    @Test
    fun prune_keepsCameraAndMicHistoryThirtyDaysWhateverTheRetention() = runBlocking {
        fun event(start: Long) = SensorEventEntity(
            sensor = "CAMERA", startTime = start, endTime = start + 1_000, source = "camera 0",
            packageName = null, confidence = "UNKNOWN", attributionMethod = "test", screenOn = true,
            locked = false, quietHours = false, inCall = false, openedRecently = false,
        )
        db.sensorEventDao().insert(event(now - 31 * day))
        db.sensorEventDao().insert(event(now - 20 * day))

        // General history is kept 7 days here; camera & mic history still 30.
        PruneWorker.prune(db, now, retentionDays = 7, payloadCutoffMs = 0L)

        assertEquals(1, count("sensor_events"))
    }

    @Test
    fun prune_appliesPayloadCutoffSeparately() = runBlocking {
        db.payloadLogDao().insert(payload(now - 2 * day))
        db.payloadLogDao().insert(payload(now - 1_000))

        PruneWorker.prune(db, now, retentionDays = 30, payloadCutoffMs = now - day)
        assertEquals(1, count("payload_logs"))

        // MITM off: the flavor hook returns Long.MAX_VALUE and every payload goes.
        PruneWorker.prune(db, now, retentionDays = 30, payloadCutoffMs = Long.MAX_VALUE)
        assertEquals(0, count("payload_logs"))
    }

    @Test
    fun prune_capsRowsAcrossSeveralBatches() = runBlocking {
        repeat(10) { i -> db.connectionDao().insert(ConnectionEntity(appUid = 1, timestamp = now - i)) }

        // Batches of 2 force several delete transactions to remove the 6 extra rows.
        PruneWorker.prune(db, now, retentionDays = 30, payloadCutoffMs = 0L, maxConnections = 4, batchSize = 2)
        assertEquals(4, count("connections"))
        assertEquals(now - 3, db.query("SELECT MIN(timestamp) FROM connections", null)
            .use { it.moveToFirst(); it.getLong(0) })

        // Under the cap: nothing is deleted.
        PruneWorker.prune(db, now, retentionDays = 30, payloadCutoffMs = 0L, maxConnections = 100)
        assertEquals(4, count("connections"))
    }
}
