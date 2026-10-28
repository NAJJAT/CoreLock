package com.privacyguard.app.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.provider.Settings

/**
 * The kill switch is Android's Always-on VPN with "Block connections without VPN".
 * Only the OS can block traffic once our tunnel is gone, so the app has no switch of
 * its own: it reports what Android says and opens Android's VPN settings.
 *
 * (An in-app "VPN drop alert" used to live here. It could not fire when protection
 * really stopped: it was unregistered before the tunnel closed and died with the
 * process. Removed rather than kept as a promise it did not keep.)
 */
object KillSwitch {

    @Volatile private var service: java.lang.ref.WeakReference<VpnService>? = null

    /** True only when [lockdownStatus] is [LockdownStatus.ON]. */
    val lockdownActive: Boolean
        get() = lockdownStatus == LockdownStatus.ON

    /** What the app can say about Android's lockdown: on and off only when Android answered. */
    enum class LockdownStatus { ON, OFF, NOT_RUNNING, UNSUPPORTED }

    /**
     * Asked at each read, not cached: the user can switch Always-on and lockdown
     * while we run. Android answers only the running VPN service (API 29+), so
     * without one the state is not checked, which is never reported as on or off.
     */
    val lockdownStatus: LockdownStatus
        get() = statusOf(Build.VERSION.SDK_INT, service?.get()?.let { s ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) null
            else runCatching { s.isAlwaysOn && s.isLockdownEnabled }.getOrNull()
        })

    /** [lockdown] null: no running service to ask, or Android did not answer. */
    internal fun statusOf(sdk: Int, lockdown: Boolean?): LockdownStatus = when {
        sdk < Build.VERSION_CODES.Q -> LockdownStatus.UNSUPPORTED
        lockdown == null -> LockdownStatus.NOT_RUNNING
        lockdown -> LockdownStatus.ON
        else -> LockdownStatus.OFF
    }

    /** Call from the running service; isAlwaysOn/isLockdownEnabled only answer for the calling VpnService. */
    fun attach(service: VpnService) {
        this.service = java.lang.ref.WeakReference(service)
    }

    fun detach() {
        service = null
    }

    /** System VPN settings, where the user turns on Always-on VPN and "Block connections without VPN". */
    fun openAlwaysOnSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
