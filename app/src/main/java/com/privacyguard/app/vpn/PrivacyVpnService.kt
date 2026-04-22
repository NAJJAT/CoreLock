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
import com.privacyguard.app.core.app.AppResolver
import com.privacyguard.app.core.blocklist.BlocklistManager
import com.privacyguard.app.core.pcap.PcapWriter
import com.privacyguard.app.core.stats.StatsManager
import com.privacyguard.app.service.notification.NotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

        @Volatile
        var isPcapEnabled = false
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val packetProcessor = PacketProcessor()
    private lateinit var notificationService: NotificationService
    @Volatile
    private var isActive = false
    private var lastBatteryTick = 0L

    override fun onCreate() {
        super.onCreate()
        notificationService = NotificationService(this)
        AppResolver.initialize(this)
        KillSwitch.initialize(this)
        createNotificationChannel()
        BlocklistManager.initialize(this)
        StatsManager.setBlocklistSize(BlocklistManager.getSize())
        packetProcessor.attachNotificationService(notificationService)
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
        UidMapper.cleanup()
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
            KillSwitch.startMonitoring(this)
            notificationService.vpnStarted()

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
            val buffer = BufferPool.acquire()

            try {
                while (isActive) {
                    val length = inputStream.read(buffer)
                    if (length > 0) {
                        if (isPcapEnabled) {
                            PcapWriter.startCapture(this@PrivacyVpnService)
                            PcapWriter.writePacket(buffer.copyOf(length))
                        }
                        when (val decision = packetProcessor.process(buffer, length)) {
                            is PacketDecision.Blocked -> Log.d(TAG, decision.reason)
                            PacketDecision.Pass -> Unit
                        }
                        checkBatteryOptimization()
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Packet capture failed", e)
                }
            } finally {
                if (PcapWriter.isCapturing()) {
                    PcapWriter.stopCapture()
                }
                BufferPool.release(buffer)
                inputStream.close()
            }
        }
    }

    private fun checkBatteryOptimization() {
        val now = System.currentTimeMillis()
        if (now - lastBatteryTick >= 60_000L) {
            lastBatteryTick = now
            Log.d(TAG, "VPN active, packets=${StatsManager.snapshot.value.totalPackets}")
        }
    }

    private fun stopVpn() {
        val wasRunning = isRunning
        isActive = false
        isRunning = false
        KillSwitch.stopMonitoring()
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing TUN", e)
        }
        vpnInterface = null
        if (PcapWriter.isCapturing()) {
            PcapWriter.stopCapture()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (wasRunning) {
            notificationService.vpnStopped()
        }
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

    private object BufferPool {
        private val pool = ArrayDeque<ByteArray>()

        @Synchronized
        fun acquire(): ByteArray = if (pool.isEmpty()) ByteArray(32_767) else pool.removeFirst()

        @Synchronized
        fun release(buffer: ByteArray) {
            if (pool.size < 8) {
                pool.addLast(buffer)
            }
        }
    }
}
