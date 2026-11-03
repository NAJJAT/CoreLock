package com.privacyguard.app.core.power

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Whether the app process (where protection runs) last ended because Android or the phone
 * maker's battery manager killed it, rather than a crash, an ANR or the user. Home shows its
 * battery card for an "Optimized" app only then: "Optimized" is Android's default, and on
 * most phones it never stops a foreground VPN, so advice for every new user would be noise.
 *
 * Read only. Android keeps the exit records itself; CoreLock stores nothing.
 */
object SystemStopCheck {

    private const val TAG = "SystemStopCheck"

    enum class Result {
        /** Killed by the system (low memory, a battery manager, a freeze). */
        SYSTEM,
        /** Crash, ANR, the user (force stop, swipe from recents), or an update. */
        NOT_SYSTEM,
        /** Android 10 and older keep no exit records; or there is no record. */
        UNKNOWN,
    }

    fun lastExit(context: Context): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Result.UNKNOWN
        val am = context.getSystemService(ActivityManager::class.java) ?: return Result.UNKNOWN
        return runCatching {
            // Newest first. Only the main process: the :watchdog process outlives it on purpose.
            am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS)
                .firstOrNull { it.processName == context.packageName }
                ?.let { classify(it.reason) }
                ?: Result.UNKNOWN
        }.onFailure { Log.w(TAG, "exit reasons unreadable: ${it.javaClass.simpleName}") }
            .getOrDefault(Result.UNKNOWN)
    }

    /**
     * Pure, so it runs as a JVM test. An allow-list: a reason added in a later Android
     * version reads as not-system, so the card never claims a kill it can't name.
     * SIGNALED and OTHER are listed because OEM battery managers end apps that way.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun classify(reason: Int): Result = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_OTHER,
        REASON_FREEZER -> Result.SYSTEM
        ApplicationExitInfo.REASON_UNKNOWN -> Result.UNKNOWN
        else -> Result.NOT_SYSTEM
    }

    /** ApplicationExitInfo.REASON_FREEZER is API 33; the value is fixed by the platform. */
    private const val REASON_FREEZER = 14
    private const val MAX_RECORDS = 8
}
