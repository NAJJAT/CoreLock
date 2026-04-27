package com.privacyguard.mdm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives [Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED] from the OS when
 * the EMM pushes a new managed-configuration policy and immediately applies it.
 *
 * This is a SEPARATE receiver from [DeviceAdminReceiver] because the OS sends
 * APPLICATION_RESTRICTIONS_CHANGED without holding BIND_DEVICE_ADMIN permission.
 * If that action were registered on the Device Admin receiver (which requires
 * BIND_DEVICE_ADMIN to send broadcasts to it), the OS would be silently blocked
 * and policy changes would never be applied.
 */
class MdmConfigReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MdmConfigReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED) return
        Log.i(TAG, "Managed configuration changed — applying new policy")

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ManagedConfigApplier.apply(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply managed configuration", e)
            } finally {
                pending.finish()
            }
        }
    }
}
