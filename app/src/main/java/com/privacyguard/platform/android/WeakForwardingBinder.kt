package com.privacyguard.platform.android

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.lang.ref.WeakReference

/**
 * Hands Android a binder that forwards every call to [target] but does not keep it
 * alive.
 *
 * Why: the binder `VpnService.onBind` returns (`VpnService.Callback`) is an inner
 * class, so it holds the whole service. Android's system process keeps its reference
 * to that binder until its own garbage collection, long after the service was
 * destroyed; every stopped PrivacyVpnService then stayed in memory with its filters
 * and lists (about 30 MB each, measured: 42 → 169 MB Java heap after 5 start/stop
 * cycles, OutOfMemoryError after more). The service holds [target] strongly until
 * onDestroy, so calls (onRevoke) reach it exactly as before while it runs.
 */
internal class WeakForwardingBinder(target: IBinder) : Binder() {

    private val target = WeakReference(target)

    /** False once the forwarded binder was collected; for tests. */
    internal val isTargetAlive: Boolean get() = target.get() != null

    // A call after the service is gone has no one to reach: unhandled, as for an
    // unknown code.
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
        target.get()?.transact(code, data, reply, flags) ?: false
}
