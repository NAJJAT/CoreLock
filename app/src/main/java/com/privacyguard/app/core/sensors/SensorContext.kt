package com.privacyguard.app.core.sensors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.KeyguardManager
import android.media.AudioManager
import android.os.PowerManager
import java.util.Calendar
import java.util.TimeZone

/** The circumstances of one camera/microphone use, as observed at the time. */
data class SensorContext(
    val screenOn: Boolean,
    val locked: Boolean,
    /** Local hour 0–23 when the use started. */
    val hourOfDay: Int,
    val quietHours: Boolean,
    /** A phone or VoIP call is in progress: the microphone is expected. */
    val inCall: Boolean,
    /** The attributed app was brought to the screen by the user in the last 5 minutes. */
    val openedRecently: Boolean,
    /** Milliseconds since the screen last turned on, or null if not seen since watching began. */
    val sinceScreenOnMs: Long?,
    /**
     * Bytes the attributed app (or, if unknown, the busiest candidate) uploaded
     * within ±60 s of the use; null until the window has passed.
     */
    val uploadBytes: Long? = null,
    /** Which app [uploadBytes] belongs to. */
    val uploadPackage: String? = null,
) {
    companion object {
        /** "Then it uploaded": at least this much sent around the use. */
        const val BURST_BYTES = 256 * 1024L
        const val BURST_WINDOW_MS = 60_000L
        const val OPENED_RECENTLY_MS = 5 * 60_000L
    }

    val networkBurst: Boolean get() = (uploadBytes ?: 0) >= BURST_BYTES
}

/** Quiet hours as minutes after midnight; the range may wrap past midnight. */
data class QuietHours(val startMinute: Int, val endMinute: Int) {
    fun contains(time: Long, zone: TimeZone = TimeZone.getDefault()): Boolean {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = time }
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (startMinute <= endMinute) minute in startMinute until endMinute
        else minute >= startMinute || minute < endMinute
    }
}

/** Live device state, behind an interface so the rules can be tested with fakes. */
interface DeviceState {
    fun isScreenOn(): Boolean
    fun isLocked(): Boolean
    fun isInCall(): Boolean
    /** Wall-clock time the screen last turned on, if seen. */
    fun lastScreenOnAt(): Long?
}

/** [DeviceState] from PowerManager, KeyguardManager and AudioManager; no permissions needed. */
class AndroidDeviceState(private val context: Context) : DeviceState {
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var screenOnAt: Long? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) screenOnAt = System.currentTimeMillis()
        }
    }

    fun start() {
        // A system broadcast, so a non-exported receiver still gets it.
        androidx.core.content.ContextCompat.registerReceiver(
            context, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun stop() {
        runCatching { context.unregisterReceiver(screenReceiver) }
    }

    override fun isScreenOn() = power.isInteractive
    override fun isLocked() = keyguard.isDeviceLocked
    // MODE_IN_CALL: phone call; MODE_IN_COMMUNICATION: VoIP (WhatsApp, Meet, …).
    override fun isInCall() = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION
    override fun lastScreenOnAt() = screenOnAt
}

/**
 * Captures [SensorContext] for a use. [openedSince] and [uidOf] come from the
 * attributor and PackageManager; [uploads] reads [UploadMeter].
 */
class SensorContextBuilder(
    private val device: DeviceState,
    private val quietHours: () -> QuietHours,
    private val openedSince: (since: Long, until: Long) -> Set<String>,
    private val uidOf: (String) -> Int?,
    private val uploads: (uid: Int, from: Long, to: Long) -> Long = UploadMeter::bytesBetween,
    private val zone: () -> TimeZone = TimeZone::getDefault,
) {
    /** Context at the moment the use started (no upload figure yet). */
    fun atStart(use: SensorUse, attribution: Attribution): SensorContext {
        val cal = Calendar.getInstance(zone()).apply { timeInMillis = use.startTime }
        val pkg = attribution.packageName
        return SensorContext(
            screenOn = device.isScreenOn(),
            locked = device.isLocked(),
            hourOfDay = cal.get(Calendar.HOUR_OF_DAY),
            quietHours = quietHours().contains(use.startTime, zone()),
            inCall = device.isInCall(),
            openedRecently = pkg != null &&
                pkg in openedSince(use.startTime - SensorContext.OPENED_RECENTLY_MS, use.startTime + SensorAttributor.STARTUP_WINDOW_MS),
            sinceScreenOnMs = device.lastScreenOnAt()?.let { use.startTime - it }?.takeIf { it >= 0 },
        )
    }

    /**
     * Adds the upload check once [SensorContext.BURST_WINDOW_MS] has passed after the
     * use ended: the attributed app's upload, or the largest among the candidates.
     */
    fun withUploads(context: SensorContext, use: SensorUse, attribution: Attribution): SensorContext {
        val end = use.endTime ?: use.startTime
        val from = use.startTime - SensorContext.BURST_WINDOW_MS
        val to = end + SensorContext.BURST_WINDOW_MS
        val packages = attribution.packageName?.let { listOf(it) } ?: attribution.candidates
        val best = packages
            .mapNotNull { pkg -> uidOf(pkg)?.let { uid -> pkg to uploads(uid, from, to) } }
            .maxByOrNull { it.second }
        return context.copy(uploadBytes = best?.second ?: 0L, uploadPackage = best?.first)
    }
}
