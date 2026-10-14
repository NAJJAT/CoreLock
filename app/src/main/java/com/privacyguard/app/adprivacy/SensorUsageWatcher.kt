package com.privacyguard.app.adprivacy

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.privacyguard.app.data.db.SensorEventDao
import com.privacyguard.app.data.db.SensorEventEntity
import com.privacyguard.platform.android.ForegroundAppDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Camera & Mic Watch: records when the microphone or a camera is in use.
 *
 * Uses the two public signals Android gives any app:
 *  - AudioManager recording callbacks (active recordings, anonymised: no app name)
 *  - CameraManager availability callbacks (a camera became busy / free)
 * Neither names the app, so the app on screen when use started is stored as the
 * likely user. Runs only while the protection service runs.
 */
class SensorUsageWatcher(
    private val context: Context,
    private val dao: SensorEventDao,
    private val scope: CoroutineScope,
    private val foreground: ForegroundAppDetector,
) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val camera = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val handler = Handler(Looper.getMainLooper())

    // One writer, so a start's row id is always known before its end is processed.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val db = Dispatchers.IO.limitedParallelism(1)

    // Touched only on [db].
    private val openMic = HashMap<Int, Long>()  // audio session id -> row id
    // A camera app opens several camera ids at once (logical + physical lenses);
    // they form one use, which ends when the last of them is released.
    private val busyCameras = HashSet<String>()
    private var cameraRow: Long? = null

    private val audioCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            onRecordings(configs.associate { it.clientAudioSessionId to sourceName(it.clientAudioSource) })
        }
    }

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) = cameraBusy(cameraId)
        override fun onCameraAvailable(cameraId: String) = cameraFree(cameraId)
    }

    fun start() {
        // Queued on [db] before any callback can insert a new row.
        scope.launch(db) { runCatching { dao.closeOrphans() } }
        try {
            audio.registerAudioRecordingCallback(audioCallback, handler)
            // The callback only reports changes; pick up a recording already running.
            audioCallback.onRecordingConfigChanged(audio.activeRecordingConfigurations)
            camera.registerAvailabilityCallback(cameraCallback, handler)
        } catch (e: Exception) {
            Log.w(TAG, "Sensor watch unavailable: ${e.message}")
        }
    }

    fun stop() {
        runCatching { audio.unregisterAudioRecordingCallback(audioCallback) }
        runCatching { camera.unregisterAvailabilityCallback(cameraCallback) }
        val now = System.currentTimeMillis()
        // Blocking (briefly): the service scope is cancelled right after stop(), and
        // a use still open must be closed at the moment watching ended.
        runCatching {
            kotlinx.coroutines.runBlocking(db) {
                kotlinx.coroutines.withTimeout(1_000) {
                    (openMic.values + listOfNotNull(cameraRow)).forEach { dao.end(it, now) }
                    openMic.clear(); busyCameras.clear(); cameraRow = null
                }
            }
        }
    }

    private fun onRecordings(active: Map<Int, String>) {
        val now = System.currentTimeMillis()
        val screenOn = power.isInteractive
        scope.launch(db) {
            (openMic.keys - active.keys).forEach { session -> openMic.remove(session)?.let { dao.end(it, now) } }
            (active.keys - openMic.keys).forEach { session ->
                val id = insert(SensorEventEntity.SENSOR_MIC, now, screenOn, active[session])
                openMic[session] = id
                attributeLater(id, now, screenOn)
            }
        }
    }

    private fun cameraBusy(cameraId: String) {
        val now = System.currentTimeMillis()
        val screenOn = power.isInteractive
        scope.launch(db) {
            busyCameras += cameraId
            Log.d(TAG, "camera $cameraId busy (busy=$busyCameras, row=$cameraRow)")
            if (cameraRow == null) {
                val id = insert(SensorEventEntity.SENSOR_CAMERA, now, screenOn, null)
                cameraRow = id
                attributeLater(id, now, screenOn)
            }
        }
    }

    private fun cameraFree(cameraId: String) {
        val now = System.currentTimeMillis()
        scope.launch(db) {
            val wasBusy = busyCameras.remove(cameraId)
            Log.d(TAG, "camera $cameraId free (wasBusy=$wasBusy, busy=$busyCameras, row=$cameraRow)")
            if (!wasBusy || busyCameras.isNotEmpty()) return@launch
            cameraRow?.let { dao.end(it, now) }
            cameraRow = null
        }
    }

    private suspend fun insert(sensor: String, at: Long, screenOn: Boolean, detail: String?) = dao.insert(
        SensorEventEntity(
            sensor = sensor, startedAt = at, endedAt = null,
            screenOnAtStart = screenOn, likelyPackage = null, detail = detail,
        )
    )

    /**
     * An app usually grabs the camera/mic the instant it comes on screen, before
     * Android has logged it as foreground; asking right away returns the previous
     * app. So ask a little later, for the moment just after the start.
     */
    private fun attributeLater(rowId: Long, startedAt: Long, screenOn: Boolean) {
        // Screen off: the last app on screen is not the one using the sensor, so name none.
        if (!screenOn) return
        scope.launch(Dispatchers.IO) {
            delay(ATTRIBUTION_DELAY_MS)
            val pkg = likelyUser(screenOn, foreground.packageAt(startedAt + ATTRIBUTION_WINDOW_MS), context.packageName)
            if (pkg != null) dao.setLikelyPackage(rowId, pkg)
        }
    }

    private fun sourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        else -> "SOURCE_$source"
    }

    companion object {
        /**
         * Which app most likely used the camera or mic: the one on screen just after
         * use started. None when the screen was off (the last app on screen is not
         * the user then), and never PrivacyGuard itself, which uses neither.
         */
        fun likelyUser(screenOn: Boolean, onScreen: String?, ownPackage: String): String? =
            onScreen?.takeIf { screenOn && it != ownPackage }

        private const val TAG = "SensorUsageWatcher"
        private const val ATTRIBUTION_DELAY_MS = 2_500L
        private const val ATTRIBUTION_WINDOW_MS = 1_500L
    }
}
