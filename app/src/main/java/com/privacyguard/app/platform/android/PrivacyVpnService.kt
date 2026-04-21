/**
 * PrivacyVpnService.kt
 * 
 * Main VPN Service for PrivacyGuard
 * 
 * What it does:
 * =============
 * - Extends Android VpnService
 * - Creates and manages TUN interface
 * - Routes all device traffic through PrivacyGuard
 * - Integrates all components: packet capture, filtering, forwarding
 * 
 * Lifecycle:
 * ==========
 * 1. User clicks "Start VPN" in UI
 * 2. System prompts for VPN permission
 * 3. onStartCommand() creates TUN interface
 * 4. Starts capture loop (TunReader)
 * 5. Starts forwarders (TcpForwarder, UdpForwarder)
 * 6. onDestroy() cleans up all resources
 * 
 * Permissions Required:
 * =====================
 * - android.permission.INTERNET
 * - android.permission.ACCESS_NETWORK_STATE
 * - VpnService permission (system grants)
 * 
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.platform.android

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
import com.privacyguard.app.core.filter.FilterEngine
import com.privacyguard.app.core.filter.FilterRule
import com.privacyguard.app.core.packet.IpPacket
import com.privacyguard.app.core.packet.TcpPacket
import com.privacyguard.app.core.packet.UdpPacket
import com.privacyguard.app.core.session.SessionKey
import com.privacyguard.app.core.session.SessionTable
import com.privacyguard.app.data.repository.BlocklistRepository
import com.privacyguard.app.data.repository.ConnectionRepository
import com.privacyguard.app.data.repository.RulesRepository
import com.privacyguard.app.vpn.firewall.AppFilter
import com.privacyguard.app.vpn.firewall.DomainFilter
import com.privacyguard.app.vpn.firewall.IpFilter
import com.privacyguard.app.vpn.forwarder.TcpForwarder
import com.privacyguard.app.vpn.forwarder.UdpForwarder
import com.privacyguard.app.vpn.tunnel.TunReader
import com.privacyguard.app.vpn.tunnel.TunWriter
import kotlinx.coroutines.*
import java.io.FileDescriptor
import java.net.InetSocketAddress

/**
 * Main VPN Service for PrivacyGuard
 */
class PrivacyVpnService : VpnService() {
    
    companion object {
        private const val TAG = "PrivacyVpnService"
        private const val CHANNEL_ID = "privacyguard_vpn"
        private const val NOTIFICATION_ID = 1
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_MTU = 1500
        
        // Status flags for UI
        var isRunning = false
            private set
        var lastError: String? = null
            private set
    }
    
    // Core components
    private lateinit var sessionTable: SessionTable
    private lateinit var filterEngine: FilterEngine
    private lateinit var appFilter: AppFilter
    private lateinit var domainFilter: DomainFilter
    private lateinit var ipFilter: IpFilter
    private lateinit var tcpForwarder: TcpForwarder
    private lateinit var udpForwarder: UdpForwarder
    private lateinit var tunReader: TunReader
    private lateinit var tunWriter: TunWriter
    
    // Repositories
    private lateinit var rulesRepository: RulesRepository
    private lateinit var blocklistRepository: BlocklistRepository
    private lateinit var connectionRepository: ConnectionRepository
    
    // TUN interface
    private var tunInterface: ParcelFileDescriptor? = null
    
    // Coroutine scope
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // ============================================================
    // Lifecycle Methods
    // ============================================================
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VPN Service created")
        createNotificationChannel()
        initializeComponents()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "VPN Service starting")
        
        if (!isRunning) {
            startVpn()
        }
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        Log.d(TAG, "VPN Service destroying")
        stopVpn()
        super.onDestroy()
    }
    
    // ============================================================
    // Initialization
    // ============================================================
    
    private fun initializeComponents() {
        // Initialize session table (heart of VPN)
        sessionTable = SessionTable()
        
        // Initialize filters
        filterEngine = FilterEngine()
        appFilter = AppFilter(this)
        domainFilter = DomainFilter()
        ipFilter = IpFilter()
        
        // Initialize repositories
        val database = com.privacyguard.app.data.db.AppDatabase.getInstance(this)
        rulesRepository = RulesRepository(database.rulesDao())
        blocklistRepository = BlocklistRepository(database.blocklistDao())
        connectionRepository = ConnectionRepository(database.connectionDao())
        
        // Initialize forwarders
        tunWriter = TunWriter(0) // Will set FD after TUN creation
        tcpForwarder = TcpForwarder(sessionTable, tunWriter)
        udpForwarder = UdpForwarder(sessionTable, tunWriter)
        
        // Load rules from database
        serviceScope.launch {
            loadRules()
            loadBlocklist()
        }
        
        // Initialize app filter
        appFilter.initialize()
    }
    
    private suspend fun loadRules() {
        try {
            val rules = rulesRepository.getAllRules()
            for (rule in rules) {
                filterEngine.addRule(rule)
            }
            Log.d(TAG, "Loaded ${rules.size} rules from database")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load rules", e)
        }
    }
    
    private suspend fun loadBlocklist() {
        try {
            val entries = blocklistRepository.getAllEntries()
            val blocklistEntries = entries.map { entry ->
                com.privacyguard.app.core.filter.BlocklistEntry(
                    domain = entry.domain,
                    source = entry.source,
                    category = entry.category,
                    lastUpdated = entry.lastUpdated
                )
            }
            domainFilter.updateBlocklist(blocklistEntries)
            Log.d(TAG, "Loaded ${blocklistEntries.size} blocklist entries")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load blocklist", e)
        }
    }
    
    // ============================================================
    // VPN Management
    // ============================================================
    
    private fun startVpn() {
        try {
            // Build VPN interface
            val builder = Builder()
            builder.setSession("PrivacyGuard VPN")
            builder.addAddress(VPN_ADDRESS, 24)
            builder.addDnsServer("8.8.8.8")
            builder.addDnsServer("8.8.4.4")
            builder.addRoute("0.0.0.0", 0)  // Route all traffic
            builder.addRoute("::", 0)       // IPv6 support
            builder.setMtu(VPN_MTU)
            builder.setBlocking(true)
            
            // Exclude this app from VPN (avoid loop)
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Could not exclude self from VPN", e)
            }
            
            // Establish TUN interface
            tunInterface = builder.establish()
            
            if (tunInterface == null) {
                lastError = "Failed to establish TUN interface"
                Log.e(TAG, lastError!!)
                return
            }
            
            val tunFd = tunInterface!!.fileDescriptor
            
            // Recreate TunWriter with correct FD
            tunWriter = TunWriter(tunFd)
            
            // Start components
            startForwarders()
            startPacketCapture(tunFd)
            
            isRunning = true
            lastError = null
            
            // Start foreground notification
            startForeground(NOTIFICATION_ID, createNotification())
            
            Log.d(TAG, "VPN started successfully")
            
        } catch (e: Exception) {
            lastError = e.message
            Log.e(TAG, "Failed to start VPN", e)
            stopVpn()
        }
    }
    
    private fun startForwarders() {
        tcpForwarder.start()
        udpForwarder.start()
        Log.d(TAG, "Forwarders started")
    }
    
    private fun startPacketCapture(tunFd: Int) {
        // Create packet callback
        val callback = object : TunReader.PacketReadCallback {
            override fun onPacketRead(buffer: java.nio.ByteBuffer, size: Int) {
                processPacket(buffer, size)
            }
            
            override fun onReadError(error: java.io.IOException) {
                Log.e(TAG, "Packet read error", error)
                lastError = error.message
            }
        }
        
        tunReader = TunReader(tunFd, callback)
        tunReader.start()
        Log.d(TAG, "Packet capture started")
    }
    
    private fun stopVpn() {
        isRunning = false
        
        // Stop components
        tunReader.stop()
        tcpForwarder.stop()
        udpForwarder.stop()
        
        // Close TUN interface
        try {
            tunInterface?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing TUN", e)
        }
        tunInterface = null
        
        // Stop foreground
        stopForeground(true)
        
        Log.d(TAG, "VPN stopped")
    }
    
    // ============================================================
    // Packet Processing
    // ============================================================
    
    private fun processPacket(buffer: java.nio.ByteBuffer, size: Int) {
        val data = ByteArray(size)
        buffer.get(data)
        
        // Parse IP packet
        val ipPacket = IpPacket.parse(data, size) ?: return
        
        // Get app info from UID (simplified - in production, get from socket)
        val appUid = getAppUidFromPacket(ipPacket)
        val appInfo = appFilter.getAppByUid(appUid)
        
        // Check firewall rules
        val decision = evaluateFirewall(ipPacket, appInfo?.packageName)
        
        if (decision.isBlocked) {
            // Block the packet
            recordBlockedConnection(ipPacket, appInfo, decision.reason)
            return
        }
        
        // Process based on protocol
        when {
            ipPacket.isTcp() -> processTcpPacket(ipPacket, appInfo)
            ipPacket.isUdp() -> processUdpPacket(ipPacket, appInfo)
            else -> {
                // Forward other protocols (ICMP, etc.)
                tunWriter.write(data)
            }
        }
        
        // Record connection
        recordConnection(ipPacket, appInfo, decision)
    }
    
    private fun evaluateFirewall(ipPacket: IpPacket, packageName: String?): FirewallDecision {
        val destIp = IpPacket.ipToString(ipPacket.destinationAddress)
        
        // Check app filter
        if (packageName != null && filterEngine.isAppBypassed(packageName)) {
            return FirewallDecision(false, "App bypassed")
        }
        
        // Check IP filter
        val ipResult = ipFilter.checkIp(destIp)
        if (ipResult.isBlocked) {
            return FirewallDecision(true, ipResult.reason)
        }
        
        // Domain filter (if domain available)
        // In production, extract domain from DNS cache
        
        return FirewallDecision(false, "Allowed")
    }
    
    private fun processTcpPacket(ipPacket: IpPacket, appInfo: com.privacyguard.app.vpn.firewall.AppInfo?) {
        // Parse TCP packet
        val tcpPacket = TcpPacket.parse(ipPacket.payload, ipPacket.payload.size) ?: return
        
        // Create session key
        val key = SessionKey(
            srcIp = ipPacket.sourceAddress,
            srcPort = tcpPacket.sourcePort,
            dstIp = ipPacket.destinationAddress,
            dstPort = tcpPacket.destinationPort,
            protocol = 6
        )
        
        // Get or create session
        var session = sessionTable.getOutgoing(key)
        
        if (session == null && tcpPacket.isSyn()) {
            // New connection
            val socket = java.net.Socket()
            try {
                socket.connect(InetSocketAddress(IpPacket.ipToString(ipPacket.destinationAddress), tcpPacket.destinationPort), 10000)
                session = sessionTable.register(key, socket, appInfo?.uid ?: -1, appInfo?.appName ?: "Unknown")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to ${IpPacket.ipToString(ipPacket.destinationAddress)}:${tcpPacket.destinationPort}", e)
                return
            }
        }
        
        if (session != null) {
            tcpForwarder.processClientPacket(session, tcpPacket, tcpPacket.payload)
        }
    }
    
    private fun processUdpPacket(ipPacket: IpPacket, appInfo: com.privacyguard.app.vpn.firewall.AppInfo?) {
        // Parse UDP packet
        val udpPacket = UdpPacket.parse(ipPacket.payload, ipPacket.payload.size) ?: return
        
        // Create session key
        val key = SessionKey(
            srcIp = ipPacket.sourceAddress,
            srcPort = udpPacket.sourcePort,
            dstIp = ipPacket.destinationAddress,
            dstPort = udpPacket.destinationPort,
            protocol = 17
        )
        
        udpForwarder.processClientPacket(key, udpPacket.payload, udpPacket.payload.size)
    }
    
    private fun getAppUidFromPacket(ipPacket: IpPacket): Int {
        // In production, retrieve UID from /proc/net/tcp or socket
        // For MVP, return a placeholder
        return android.os.Process.myUid()
    }
    
    // ============================================================
    // Recording
    // ============================================================
    
    private fun recordConnection(ipPacket: IpPacket, appInfo: com.privacyguard.app.vpn.firewall.AppInfo?, decision: FirewallDecision) {
        serviceScope.launch {
            try {
                connectionRepository.recordConnection(
                    appUid = appInfo?.uid ?: -1,
                    appName = appInfo?.appName ?: "Unknown",
                    destinationIp = IpPacket.ipToString(ipPacket.destinationAddress),
                    destinationPort = 0, // Extract from transport protocol
                    protocol = if (ipPacket.isTcp()) "TCP" else if (ipPacket.isUdp()) "UDP" else "IP",
                    domain = null,
                    bytesSent = ipPacket.payload.size.toLong(),
                    bytesReceived = 0,
                    wasBlocked = decision.isBlocked,
                    blockReason = if (decision.isBlocked) decision.reason else null
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record connection", e)
            }
        }
    }
    
    private fun recordBlockedConnection(ipPacket: IpPacket, appInfo: com.privacyguard.app.vpn.firewall.AppInfo?, reason: String) {
        // Update statistics
        if (appInfo != null) {
            appFilter.recordBlocked(appInfo.uid)
        }
        
        // Record in database
        recordConnection(ipPacket, appInfo, FirewallDecision(true, reason))
    }
    
    // ============================================================
    // UI Communication
    // ============================================================
    
    fun getStats(): VpnStats {
        return VpnStats(
            isRunning = isRunning,
            activeTcpConnections = tcpForwarder.getStats().activeConnections,
            activeUdpSessions = udpForwarder.getStats().activeSessions,
            packetsRead = tunReader.getTotalPacketsRead(),
            packetsWritten = tunWriter.getTotalPacketsWritten(),
            bytesRead = tunReader.getTotalBytesRead(),
            bytesWritten = tunWriter.getTotalBytesWritten(),
            blockedCount = filterEngine.getStats().totalBlocks
        )
    }
    
    // ============================================================
    // Notification
    // ============================================================
    
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
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PrivacyGuard")
            .setContentText("VPN is active - Protecting your privacy")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}

// ============================================================
// Data Classes
// ============================================================

data class FirewallDecision(
    val isBlocked: Boolean,
    val reason: String
)

data class VpnStats(
    val isRunning: Boolean,
    val activeTcpConnections: Int,
    val activeUdpSessions: Int,
    val packetsRead: Long,
    val packetsWritten: Long,
    val bytesRead: Long,
    val bytesWritten: Long,
    val blockedCount: Long
)