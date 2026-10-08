package com.privacyguard.app.core.sensors

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Detects camera and microphone use through Android's system signals:
 *  - camera: [CameraManager.AvailabilityCallback] — a camera becomes unavailable
 *    while any app has it open, and available again when it is closed;
 *  - microphone: [AudioManager.AudioRecordingCallback] — the system's list of
 *    active recordings, with each recording's audio source.
 *
 * Callbacks only, no polling. Since Android 10 these signals do not say which app
 * is recording (the system hides that from other apps); attribution is done
 * separately and never assumed here. CoreLock itself never opens the camera or
 * microphone, so every use reported here belongs to something else.
 *
 * What this cannot see: anything that bypasses the camera and audio services
 * (root-level or kernel spyware).
 */
class SensorAccessMonitor(
    context: Context,
    listener: SensorSessionTracker.Listener,
    clock: () -> Long = System::currentTimeMillis,
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val tracker = SensorSessionTracker(clock, listener)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) = tracker.cameraOpened(cameraId)
        override fun onCameraAvailable(cameraId: String) = tracker.cameraClosed(cameraId)
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) =
            tracker.recordingsChanged(activeRecordings(configs.orEmpty()))
    }

    val isRunning: Boolean get() = thread != null

    fun start() {
        if (thread != null) return
        val t = HandlerThread("sensor-watch").also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        h.post {
            runCatching { cameraManager?.registerAvailabilityCallback(cameraCallback, h) }
                .onFailure { Log.w(TAG, "Camera watch unavailable: ${it.message}") }
            runCatching {
                audioManager?.registerAudioRecordingCallback(recordingCallback, h)
                // Recordings already running when we start.
                tracker.recordingsChanged(activeRecordings(audioManager?.activeRecordingConfigurations.orEmpty()))
            }.onFailure { Log.w(TAG, "Microphone watch unavailable: ${it.message}") }
        }
        // Posted after registration, so the initial availability callbacks (which the
        // camera service delivers on registration) are flagged as already in progress.
        h.post { tracker.startWatching() }
        Log.i(TAG, "Camera & microphone watch started")
    }

    fun stop() {
        val h = handler ?: return
        h.post {
            runCatching { cameraManager?.unregisterAvailabilityCallback(cameraCallback) }
            runCatching { audioManager?.unregisterAudioRecordingCallback(recordingCallback) }
            tracker.stopWatching()
        }
        thread?.quitSafely()
        thread = null
        handler = null
        Log.i(TAG, "Camera & microphone watch stopped")
    }

    companion object {
        private const val TAG = "SensorAccessMonitor"

        /** MediaRecorder.AudioSource.HOTWORD (system API): always-on assistant listening. */
        const val AUDIO_SOURCE_HOTWORD = 1999

        private fun activeRecordings(configs: List<AudioRecordingConfiguration>): Map<Int, String> =
            configs.associate { it.clientAudioSessionId to audioSourceName(it.clientAudioSource) }

        fun audioSourceName(source: Int): String = when (source) {
            MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_UPLINK -> "VOICE_UPLINK"
            MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
            MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.REMOTE_SUBMIX -> "REMOTE_SUBMIX"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.VOICE_PERFORMANCE -> "VOICE_PERFORMANCE"
            AUDIO_SOURCE_HOTWORD -> "HOTWORD"
            else -> "SOURCE_$source"
        }
    }
}
