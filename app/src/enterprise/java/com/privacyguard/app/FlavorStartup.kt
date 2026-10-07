package com.privacyguard.app

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.privacyguard.mdm.ManagedConfigApplier
import com.privacyguard.mdm.MdmConfigReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object FlavorStartup {
    private const val TAG = "FlavorStartup"

    fun onCreate(app: Application) {
        // APPLICATION_RESTRICTIONS_CHANGED is only delivered to receivers registered
        // at runtime, so a manifest receiver never fires. System broadcasts still
        // reach a non-exported receiver.
        ContextCompat.registerReceiver(
            app,
            MdmConfigReceiver(),
            IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // The broadcast is not replayed, so pick up any policy the EMM changed
        // while the app was not running.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { ManagedConfigApplier.apply(app) }
                .onFailure { Log.e(TAG, "Failed to apply managed configuration at startup", it) }
        }
    }
}
