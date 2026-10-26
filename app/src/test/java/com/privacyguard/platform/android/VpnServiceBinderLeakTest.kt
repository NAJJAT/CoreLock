package com.privacyguard.platform.android

import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowPendingIntent
import java.lang.ref.WeakReference

/**
 * Android's system process keeps the binder from onBind long after the service was
 * destroyed. That binder must not keep the stopped service (filters, lists, sessions)
 * in memory: before the fix, every start/stop left one more service behind
 * (42 → 169 MB Java heap after 5 cycles, then OutOfMemoryError).
 */
@RunWith(RobolectricTestRunner::class)
class VpnServiceBinderLeakTest {

    private fun vpnIntent() = Intent(VpnService.SERVICE_INTERFACE)

    /** What Android keeps (the binder), and a weak handle on the destroyed service. */
    private fun bindAndDestroy(): Pair<IBinder, WeakReference<PrivacyVpnService>> {
        val controller = Robolectric.buildService(PrivacyVpnService::class.java, vpnIntent()).create()
        val binder = requireNotNull(controller.get().onBind(vpnIntent()))
        val service = WeakReference(controller.get())
        controller.destroy()
        return binder to service
    }

    private fun collected(ref: WeakReference<*>): Boolean {
        repeat(50) {
            if (ref.get() == null) return true
            System.gc()
            System.runFinalization()
            Thread.sleep(20)
        }
        return ref.get() == null
    }

    @Test
    fun destroyed_service_is_not_kept_alive_by_the_binder_android_holds() {
        val (binder, service) = bindAndDestroy()
        // Robolectric records every PendingIntent with the context that made it (the
        // watchdog alarm's, here); a real PendingIntent does not keep the service.
        ShadowPendingIntent.reset()
        assertTrue("the stopped service stays in memory", collected(service))
        // The binder itself is still there, as in Android's system process.
        assertTrue(binder.pingBinder())
    }

    @Test
    fun binder_reaches_the_running_service() {
        val controller = Robolectric.buildService(PrivacyVpnService::class.java, vpnIntent()).create()
        val binder = requireNotNull(controller.get().onBind(vpnIntent()))
        // Android revokes the VPN with this call; VpnService's own binder answers it.
        val data = Parcel.obtain()
        try {
            assertTrue(binder.transact(IBinder.LAST_CALL_TRANSACTION, data, null, IBinder.FLAG_ONEWAY))
        } finally {
            data.recycle()
        }
        assertTrue("held while the service runs", (binder as WeakForwardingBinder).isTargetAlive)
        controller.destroy()
    }

    @Test
    fun other_intents_get_no_binder() {
        val controller = Robolectric.buildService(PrivacyVpnService::class.java, Intent()).create()
        assertNull(controller.get().onBind(Intent("something.else")))
        controller.destroy()
    }

    @Test
    fun forwarding_binder_does_not_hold_its_target() {
        var target: Binder? = object : Binder() {}
        val wrapper = WeakForwardingBinder(target!!)
        assertTrue(wrapper.isTargetAlive)
        val ref = WeakReference(target)
        target = null
        assertTrue(collected(ref))
        assertFalse(wrapper.isTargetAlive)
        // A call after that is simply not handled.
        val data = Parcel.obtain()
        try {
            assertFalse(wrapper.transact(IBinder.LAST_CALL_TRANSACTION, data, null, 0))
        } finally {
            data.recycle()
        }
    }
}
