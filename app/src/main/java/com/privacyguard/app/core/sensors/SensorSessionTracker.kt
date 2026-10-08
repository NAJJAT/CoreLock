package com.privacyguard.app.core.sensors

enum class SensorType { CAMERA, MIC }

/**
 * One period of camera or microphone use, as Android reported it.
 *
 * [source] is what the system tells us about the use: the camera id, or the
 * audio source (e.g. "VOICE_COMMUNICATION"). Since Android 10 the system hides
 * which app is recording from other apps, so identity is worked out separately
 * (see attribution) and never assumed here.
 */
data class SensorUse(
    val id: Long,
    val sensor: SensorType,
    val startTime: Long,
    val endTime: Long?,
    val source: String,
    /** Already in use when watching started: the real start time is unknown. */
    val inProgressAtStart: Boolean = false,
) {
    val durationMs: Long? get() = endTime?.let { it - startTime }
}

/**
 * Turns raw availability/recording callbacks into start/end pairs. Holds no
 * Android types, so the bookkeeping is unit-testable; [SensorAccessMonitor]
 * feeds it from the real system callbacks.
 *
 * Not thread-safe: call it from one thread (the monitor's handler thread).
 */
class SensorSessionTracker(
    private val clock: () -> Long,
    private val listener: Listener,
) {
    interface Listener {
        fun onStarted(use: SensorUse)
        fun onEnded(use: SensorUse)
    }

    private var nextId = 1L
    /** Camera ids currently unavailable, and the one use they make up. */
    private val busyCameras = LinkedHashSet<String>()
    private var cameraUse: SensorUse? = null
    private val openRecordings = HashMap<Int, SensorUse>()
    private var watching = false

    /** Call once callbacks are registered; uses reported before this are "in progress". */
    fun startWatching() { watching = true }

    /**
     * A camera became unavailable: some app opened it. Opening one camera often
     * makes the others unavailable too (Samsung reports all four physical cameras
     * for one logical one), so the camera counts as one use while any id is busy.
     */
    fun cameraOpened(cameraId: String) {
        if (!busyCameras.add(cameraId)) return
        val current = cameraUse
        if (current != null) {
            cameraUse = current.copy(source = cameraSource())
            return
        }
        val use = SensorUse(nextId++, SensorType.CAMERA, clock(), null, cameraSource(), inProgressAtStart = !watching)
        cameraUse = use
        listener.onStarted(use)
    }

    /** A camera became available again (also reported for every idle camera on registration). */
    fun cameraClosed(cameraId: String) {
        if (!busyCameras.remove(cameraId) || busyCameras.isNotEmpty()) return
        val use = cameraUse ?: return
        cameraUse = null
        listener.onEnded(use.copy(endTime = clock()))
    }

    private fun cameraSource() = "camera " + busyCameras.joinToString(",")

    /**
     * The full set of active recordings, keyed by audio session id, with each one's
     * audio source. Sessions that appear start a use; sessions that vanish end it.
     */
    fun recordingsChanged(active: Map<Int, String>) {
        val now = clock()
        val ended = openRecordings.keys - active.keys
        for (session in ended) {
            val use = openRecordings.remove(session) ?: continue
            listener.onEnded(use.copy(endTime = now))
        }
        for ((session, source) in active) {
            if (session in openRecordings) continue
            val use = SensorUse(nextId++, SensorType.MIC, now, null, source, inProgressAtStart = !watching)
            openRecordings[session] = use
            listener.onStarted(use)
        }
    }

    /** Watching stops (VPN off): close everything still open at this moment. */
    fun stopWatching() {
        val now = clock()
        (listOfNotNull(cameraUse) + openRecordings.values).forEach { listener.onEnded(it.copy(endTime = now)) }
        busyCameras.clear()
        cameraUse = null
        openRecordings.clear()
        watching = false
    }

    val activeCount: Int get() = (if (cameraUse != null) 1 else 0) + openRecordings.size
}
