package com.privacyguard.app.core.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SensorSessionTrackerTest {

    private var now = 1_000L
    private val started = mutableListOf<SensorUse>()
    private val ended = mutableListOf<SensorUse>()
    private lateinit var tracker: SensorSessionTracker

    @Before
    fun setUp() {
        tracker = SensorSessionTracker({ now }, object : SensorSessionTracker.Listener {
            override fun onStarted(use: SensorUse) { started += use }
            override fun onEnded(use: SensorUse) { ended += use }
        })
        tracker.startWatching()
    }

    @Test
    fun cameraOpenAndCloseMakeOneUse() {
        tracker.cameraOpened("0")
        now += 4_000
        tracker.cameraClosed("0")
        assertEquals(1, ended.size)
        assertEquals(SensorType.CAMERA, ended[0].sensor)
        assertEquals(4_000L, ended[0].durationMs)
        assertFalse(ended[0].inProgressAtStart)
    }

    @Test
    fun allCamerasBusyForOneLogicalCameraIsOneUse() {
        // Samsung Camera: device 20 opens, cameras 0-3 all report unavailable.
        listOf("0", "1", "2", "3").forEach(tracker::cameraOpened)
        now += 7_441
        listOf("0", "1", "2").forEach(tracker::cameraClosed)
        assertTrue(ended.isEmpty())
        tracker.cameraClosed("3")
        assertEquals(1, started.size)
        assertEquals(7_441L, ended.single().durationMs)
    }

    @Test
    fun idleCamerasReportedOnRegistrationAreIgnored() {
        tracker.cameraClosed("0")
        tracker.cameraClosed("1")
        assertTrue(started.isEmpty() && ended.isEmpty())
    }

    @Test
    fun repeatedUnavailableDoesNotStartTwice() {
        tracker.cameraOpened("1")
        tracker.cameraOpened("1")
        assertEquals(1, started.size)
    }

    @Test
    fun recordingsStartAndEndBySessionId() {
        tracker.recordingsChanged(mapOf(7 to "VOICE_COMMUNICATION"))
        now += 500
        tracker.recordingsChanged(mapOf(7 to "VOICE_COMMUNICATION", 9 to "MIC"))
        now += 1_000
        tracker.recordingsChanged(mapOf(9 to "MIC"))
        assertEquals(listOf("VOICE_COMMUNICATION", "MIC"), started.map { it.source })
        assertEquals(1, ended.size)
        assertEquals(1_500L, ended[0].durationMs)
        assertEquals(1, tracker.activeCount)
    }

    @Test
    fun usesAlreadyRunningBeforeWatchingAreMarked() {
        val fresh = SensorSessionTracker({ now }, object : SensorSessionTracker.Listener {
            override fun onStarted(use: SensorUse) { started += use }
            override fun onEnded(use: SensorUse) {}
        })
        fresh.cameraOpened("0")
        fresh.startWatching()
        fresh.recordingsChanged(mapOf(1 to "MIC"))
        assertTrue(started[0].inProgressAtStart)
        assertFalse(started[1].inProgressAtStart)
    }

    @Test
    fun stoppingClosesEverythingStillOpen() {
        tracker.cameraOpened("0")
        tracker.recordingsChanged(mapOf(3 to "MIC"))
        now += 2_000
        tracker.stopWatching()
        assertEquals(2, ended.size)
        assertTrue(ended.all { it.endTime == now })
        assertEquals(0, tracker.activeCount)
    }
}
