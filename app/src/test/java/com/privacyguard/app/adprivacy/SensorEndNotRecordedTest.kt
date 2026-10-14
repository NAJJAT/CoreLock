package com.privacyguard.app.adprivacy

import androidx.room.Room
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.SensorEventDao
import com.privacyguard.app.data.db.SensorEventEntity
import com.privacyguard.app.data.db.SensorEventEntity.Companion.END_NOT_RECORDED
import com.privacyguard.app.data.db.SensorEventEntity.Companion.SENSOR_MIC
import com.privacyguard.platform.android.ForegroundAppDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A mic or camera use still open when the process died (crash, app update) was shown
 * as "in use now" and matched every "Did any app use my mic?" window afterwards: on a
 * phone, two uses from the day before turned one real use into three.
 * Real SQLite (Robolectric), in memory, so the queries themselves are tested.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric's default installs Conscrypt JVM-wide, which broke the plain-JVM TLS
// tests (DohHostnameVerificationTest) that run later in the same fork.
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class SensorEndNotRecordedTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SensorEventDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.sensorEventDao()
    }

    @After
    fun tearDown() = db.close()

    private fun mic(startedAt: Long, endedAt: Long?) = runBlocking {
        dao.insert(SensorEventEntity(sensor = SENSOR_MIC, startedAt = startedAt, endedAt = endedAt,
            screenOnAtStart = true, likelyPackage = null, detail = "MIC"))
    }

    private fun endOf(id: Long) = runBlocking { dao.recentFlow(100).first().first { it.id == id }.endedAt }

    @Test
    fun closeOrphans_marks_only_open_rows() = runBlocking {
        val open = mic(1_000, null)
        val closed = mic(2_000, 2_500)

        assertEquals(1, dao.closeOrphans())

        assertEquals(END_NOT_RECORDED, endOf(open))
        assertEquals(2_500L, endOf(closed))
        assertEquals(0, dao.closeOrphans())
    }

    @Test
    fun a_lost_end_does_not_match_a_later_window() = runBlocking {
        val yesterday = mic(HOUR, null)
        dao.closeOrphans()

        // The phone's case: searching today must not find yesterday's use.
        val today = dao.overlapping(SENSOR_MIC, 24 * HOUR, 26 * HOUR)
        assertEquals(emptyList<Long>(), today.map { it.id })
        // Searching the window it started in still finds it.
        assertEquals(listOf(yesterday), dao.overlapping(SENSOR_MIC, 0, 2 * HOUR).map { it.id })
    }

    @Test
    fun ended_and_running_uses_still_match_by_overlap() = runBlocking {
        val before = mic(HOUR, 2 * HOUR)            // ended before the window
        val across = mic(9 * HOUR, 10 * HOUR + 60)  // started before, ended inside
        val running = mic(10 * HOUR + 120, null)    // in use now (this process)

        val found = dao.overlapping(SENSOR_MIC, 10 * HOUR, 11 * HOUR).map { it.id }

        assertEquals(listOf(across, running), found)
        assertFalse(before in found)
    }

    @Test
    fun watching_starts_by_closing_rows_a_dead_process_left_open() {
        val left = mic(HOUR, null)
        val scope = CoroutineScope(SupervisorJob())
        val context = RuntimeEnvironment.getApplication()
        val watcher = SensorUsageWatcher(context, dao, scope, ForegroundAppDetector(context))
        try {
            watcher.start()
            val deadline = System.currentTimeMillis() + 5_000
            while (endOf(left) == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(END_NOT_RECORDED, endOf(left))
        } finally {
            watcher.stop()
            scope.cancel()
        }
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
