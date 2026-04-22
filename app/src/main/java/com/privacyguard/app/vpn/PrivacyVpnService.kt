package com.privacyguard.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.privacyguard.app.MainActivity
import com.privacyguard.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PrivacyVpnService : VpnService() {

    companion object {
        private const val TAG = "PrivacyVpnService"
        private const val CHANNEL_ID = "vpn_channel"
        private const val NOTIFICATION_ID = 1
        private const val VPN_ADDRESS = "10.0.0.2"

        @Volatile
        var isRunning = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    private var isActive = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "VPN Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "VPN Service starting")
        if (!isActive) {
            startVpn()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "VPN Service destroying")
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startVpn() {
        try {
            val builder = Builder()
            builder.setSession(getString(R.string.app_name))
            builder.addAddress(VPN_ADDRESS, 24)
            builder.addDnsServer("8.8.8.8")
            builder.addDnsServer("8.8.4.4")
            builder.addRoute("0.0.0.0", 0)
            builder.setMtu(1500)
            builder.setBlocking(true)

            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Could not exclude self from VPN", e)
            }

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                return
            }

            isActive = true
            isRunning = true
            startForeground(NOTIFICATION_ID, createNotification())
            startPacketCapture()

            Log.d(TAG, "VPN started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            stopVpn()
        }
    }

    private fun startPacketCapture() {
        serviceScope.launch {
            val fileDescriptor = vpnInterface?.fileDescriptor ?: return@launch
            val inputStream = java.io.FileInputStream(fileDescriptor)
            val buffer = ByteArray(32767)

            try {
                while (isActive) {
                    val length = inputStream.read(buffer)
                    if (length > 0) {
                        Log.d(TAG, "Packet received: $length bytes")
                    }
                    delay(10)
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Packet capture failed", e)
                }
            } finally {
                inputStream.close()
            }
        }
    }

    private fun stopVpn() {
        isActive = false
        isRunning = false
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing TUN", e)
        }
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.d(TAG, "VPN stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PrivacyGuard VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.privacy_protected))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
