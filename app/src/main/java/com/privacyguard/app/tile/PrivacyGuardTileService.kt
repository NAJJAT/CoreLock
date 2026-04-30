package com.privacyguard.app.tile

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.privacyguard.app.MainActivity
import com.privacyguard.app.vpn.VpnManager
import com.privacyguard.platform.android.PrivacyVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PrivacyGuardTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        stateJob = scope.launch {
            PrivacyVpnService.isRunningFlow.collect { syncTile(it) }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        stateJob?.cancel()
        stateJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { toggle() }
            return
        }
        toggle()
    }

    private fun toggle() {
        if (PrivacyVpnService.isRunning) {
            VpnManager.stopVpn(this)
        } else {
            val prepare = VpnService.prepare(this)
            if (prepare == null) {
                VpnManager.startVpn(this)
            } else {
                // VPN permission not yet granted — launch the app.
                // On API 34+ startActivityAndCollapse(Intent) is deprecated;
                // wrap in a PendingIntent to satisfy both paths.
                val intent = Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pi = PendingIntent.getActivity(
                        this, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    startActivityAndCollapse(pi)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
            }
        }
    }

    private fun syncTile(running: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "PrivacyGuard"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (running) "Protected" else "Off"
        }
        tile.updateTile()
    }
}
