package com.privacyguard

import androidx.appcompat.app.AppCompatActivity

/**
 * Shared base for all activities.
 * Tracks foreground/background state so the VPN engine can annotate
 * connections with [wasBackground] for behavioral analysis.
 */
abstract class BaseActivity : AppCompatActivity() {

    override fun onResume()  { super.onResume();  AppForegroundTracker.onActivityResumed() }
    override fun onPause()   { super.onPause();   AppForegroundTracker.onActivityPaused() }

    companion object {
        /** True when at least one activity is in the foreground. */
        val isForegrounded: Boolean get() = AppForegroundTracker.isForegrounded
    }
}

/** Process-level foreground tracker (no Android lifecycle library dependency). */
object AppForegroundTracker {
    private var resumedCount = 0

    val isForegrounded: Boolean get() = resumedCount > 0

    fun onActivityResumed() { resumedCount++ }
    fun onActivityPaused()  { if (resumedCount > 0) resumedCount-- }
}