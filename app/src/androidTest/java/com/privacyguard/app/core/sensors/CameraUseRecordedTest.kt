package com.privacyguard.app.core.sensors

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.SensorEventEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End to end on a real device: opens the system camera app, and checks that the
 * system callbacks produce a camera use with a start and an end, that it can be
 * attributed, and that it round-trips through the sensor_events table.
 *
 * Needs a device with a camera and the screen unlocked. CoreLock itself holds no
 * camera permission, so the camera app does the opening, exactly like a real use.
 */
@RunWith(AndroidJUnit4::class)
class CameraUseRecordedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun openingTheCameraIsRecorded() {
        val cameraApp = systemCameraIntent()
        assumeTrue("No system camera app on this device", cameraApp != null)

        val started = CountDownLatch(1)
        val ended = CountDownLatch(1)
        var use: SensorUse? = null
        val monitor = SensorAccessMonitor(context, object : SensorSessionTracker.Listener {
            override fun onStarted(u: SensorUse) { if (u.sensor == SensorType.CAMERA && !u.inProgressAtStart) started.countDown() }
            override fun onEnded(u: SensorUse) { if (u.sensor == SensorType.CAMERA) { use = u; ended.countDown() } }
        })
        monitor.start()
        try {
            Thread.sleep(1_000) // initial availability callbacks
            context.startActivity(cameraApp!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue("Camera never reported busy", started.await(15, TimeUnit.SECONDS))
            Thread.sleep(2_000)
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            assertTrue("Camera never reported released", ended.await(15, TimeUnit.SECONDS))
        } finally {
            monitor.stop()
        }

        val recorded = use!!
        assertNotNull(recorded.endTime)
        assertTrue((recorded.durationMs ?: 0) > 500)

        // Attribution: exact in Advanced mode, otherwise at least an honest answer.
        val attribution = SensorAttributor(context).attribute(recorded)
        if (SensorAttributor(context).hasAdvancedMode()) {
            assertEquals(Confidence.CONFIRMED, attribution.confidence)
            assertEquals(cameraApp.component!!.packageName, attribution.packageName)
        } else {
            assertTrue(attribution.confidence == Confidence.UNKNOWN || attribution.packageName != null)
        }

        // Storage round trip (in-memory: the user's history is not touched).
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            runBlocking {
                val id = db.sensorEventDao().insert(
                    SensorEventEntity(
                        sensor = recorded.sensor.name, startTime = recorded.startTime, endTime = recorded.endTime,
                        source = recorded.source, packageName = attribution.packageName,
                        confidence = attribution.confidence.name, attributionMethod = attribution.method,
                        screenOn = true, locked = false, quietHours = false, inCall = false, openedRecently = false,
                    )
                )
                val row = db.sensorEventDao().byId(id)!!
                assertEquals("CAMERA", row.sensor)
                assertEquals(recorded.startTime, row.startTime)
            }
        } finally {
            db.close()
        }
    }

    /** The preinstalled camera app's still-image activity, as an explicit intent (no chooser). */
    private fun systemCameraIntent(): Intent? {
        val pm = context.packageManager
        val match = pm.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
            ?: return null
        return Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            .setClassName(match.activityInfo.packageName, match.activityInfo.name)
    }
}
