package com.privacyguard.platform.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.room.Room
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterRule
import com.privacyguard.core.metadata.EncryptionStatus
import com.privacyguard.core.metadata.MetadataEngine
import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.packet.TcpPacket
import com.privacyguard.core.packet.UdpPacket
import com.privacyguard.core.session.Session
import com.privacyguard.core.session.SessionTable
import com.privacyguard.app.data.repository.*
import com.privacyguard.app.data.db.*
import com.privacyguard.vpn.firewall.AppFilter
import com.privacyguard.vpn.firewall.DomainFilter
import com.privacyguard.vpn.firewall.IpFilter
import com.privacyguard.vpn.forwarder.DnsHandler
import com.privacyguard.vpn.forwarder.TcpForwarder
import com.privacyguard.vpn.forwarder.UdpForwarder
import com.privacyguard.vpn.inspector.DnsAnomalyDetector
import com.privacyguard.vpn.inspector.EncryptionEnforcer
import com.privacyguard.vpn.tunnel.TunInterface
import com.privacyguard.vpn.tunnel.TunReader
import com.privacyguard.vpn.tunnel.TunWriter
import com.privacyguard.app.core.stats.ActiveConnectionInfo
import com.privacyguard.app.core.stats.ActivityInfo
import com.privacyguard.app.core.stats.AppStat
import com.privacyguard.app.core.stats.StatsManager
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

/**
 * The central Android VPN service — wires all four pillars:
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  PILLAR 1 — Metadata Engine                                          │
 * │    Every closed session → MetadataEngine.record()                    │
 * │    → ConnectionProfile (who connects where, how often, background?)  │
 * │                                                                       │
 * │  PILLAR 2 — DNS Shield                                               │
 * │    DnsHandler: blocklist + DnsAnomalyDetector                        │
 * │    → blocks known trackers, detects DNS tunneling / DGA              │
 * │                                                                       │
 * │  PILLAR 3 — Blocking                                                 │
 * │    FilterEngine: domain / IP / app / port rules                      │
 * │    3 levels: MINIMAL / STANDARD / STRICT                             │
 * │                                                                       │
 * │  PILLAR 4 — Encryption Enforcement                                   │
 * │    EncryptionEnforcer: port + TLS ClientHello inspection             │
 * │    → classify CLEARTEXT / WEAK_TLS / TLS / TLS_1.3                  │
 * │    → block cleartext if user enabled "HTTPS-only" rule               │
 * └──────────────────────────────────────────────────────────────────────┘
 */
class PrivacyVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Pillar 1: Metadata ────────────────────────────────────────────────────
    private lateinit var metadataEngine: MetadataEngine

    // ── Pillar 2: DNS Shield ──────────────────────────────────────────────────
    private lateinit var dnsHandler:       DnsHandler
    private lateinit var dnsAnomalyDetector: DnsAnomalyDetector

    // ── Pillar 3: Blocking ────────────────────────────────────────────────────
    private lateinit var filterEngine: FilterEngine
    private lateinit var domainFilter: DomainFilter
    private lateinit var ipFilter:     IpFilter
    private lateinit var appFilter:    AppFilter

    // ── Pillar 4: Encryption Enforcement ─────────────────────────────────────
    private lateinit var encEnforcer: EncryptionEnforcer

    // ── Infrastructure ────────────────────────────────────────────────────────
    private lateinit var sessionTable: SessionTable
    private lateinit var appTracker:   AppTracker
    private lateinit var notifHelper:  NotificationHelper
    private lateinit var tunInterface: TunInterface
    private lateinit var tunReader:    TunReader
    private lateinit var tunWriter:    TunWriter
    private lateinit var tcpForwarder: TcpForwarder
    private lateinit var udpForwarder: UdpForwarder

    // ── Repositories ──────────────────────────────────────────────────────────
    private lateinit var connectionRepo:  ConnectionRepo
    private lateinit var rulesRepo:       RulesRepo
    private lateinit var blocklistRepo:   BlocklistRepo
    private lateinit var metadataRepo:    MetadataRepo
    private lateinit var dnsAnomalyRepo:  DnsAnomalyRepo

    private var tunFd: ParcelFileDescriptor? = null
    private val totalBlocked = AtomicLong(0)
    private val totalCleartext = AtomicLong(0)

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopVpn(); return START_NOT_STICKY }
        if (!isRunning) startVpn()
        return START_STICKY
    }

    override fun onDestroy() { stopVpn(); scope.cancel(); super.onDestroy() }

    // ─────────────────────────────────────────────────────────────────────────
    // Start
    // ─────────────────────────────────────────────────────────────────────────

    private fun startVpn() {
        com.privacyguard.app.vpn.BootReceiver.markVpnActive(this)
        notifHelper = NotificationHelper(this).also { it.createChannels() }
        startForeground(
            NotificationHelper.NOTIFICATION_ID_VPN,
            notifHelper.buildVpnActiveNotification(0, getMainActivityClass()),
        )

        val db = buildDatabase()

        // ── Pillar 3: Blocking setup ──────────────────────────────────────────
        filterEngine  = FilterEngine()
        domainFilter  = DomainFilter()
        ipFilter      = IpFilter()
        appFilter     = AppFilter { uid -> packageManager.getNameForUid(uid) }
        appTracker    = AppTracker(this, appFilter)

        // ── Repositories ──────────────────────────────────────────────────────
        connectionRepo = ConnectionRepo(db.connectionDao())
        rulesRepo      = RulesRepo(db.rulesDao(), filterEngine)
        blocklistRepo  = BlocklistRepo(db.blocklistDao())
        metadataRepo   = MetadataRepo(db.connectionProfileDao())
        dnsAnomalyRepo = DnsAnomalyRepo(db.dnsAnomalyDao())

        // ── Pillar 4: Encryption ──────────────────────────────────────────────
        encEnforcer   = EncryptionEnforcer()

        // ── Pillar 2: DNS Shield ──────────────────────────────────────────────
        dnsAnomalyDetector = DnsAnomalyDetector()
        dnsAnomalyDetector.anomalyListener = DnsAnomalyDetector.AnomalyListener { anomaly ->
            scope.launch {
                dnsAnomalyRepo.save(anomaly)
                // Elevate high-severity anomalies to notification
                if (anomaly.severity >= 8) {
                    notifHelper.postSuspiciousAlert(
                        appName           = appTracker.labelForPackage(anomaly.packageName) ?: anomaly.packageName,
                        domain            = anomaly.domain,
                        mainActivityClass = getMainActivityClass(),
                    )
                }
            }
        }

        // ── Pillar 1: Metadata ────────────────────────────────────────────────
        metadataEngine = MetadataEngine()

        // ── Load async ───────────────────────────────────────────────────────
        scope.launch {
            rulesRepo.loadIntoEngine()
            val allDomains = blocklistRepo.allDomains()
            domainFilter.rebuild(allDomains)
            metadataEngine.knownTrackers = allDomains.toSet()
            appTracker.preloadInstalledApps()
            // Initialise Exodus cache from disk, then refresh if stale
            com.privacyguard.app.core.tracker.ExodusUpdater.initialize(applicationContext)
            com.privacyguard.app.core.tracker.ExodusUpdater.refresh(applicationContext)
        }

        scope.launch {
            RuleSyncBus.version.collect {
                rulesRepo.loadIntoEngine()
            }
        }

        // ── Session table ────────────────────────────────────────────────────
        sessionTable = SessionTable()
        sessionTable.startReaper()
        sessionTable.addListener(SessionLifecycleListener())

        // ── TUN interface ─────────────────────────────────────────────────────
        // Phase 1: IPv4-only tunnel.
        // addRoute("::", 0) was removed intentionally — TunReader has no IPv6 handlers,
        // so routing IPv6 through the VPN silently drops every IPv6 packet, making
        // modern apps (which prefer AAAA / Happy Eyeballs) unable to connect at all.
        // IPv6 traffic bypasses the VPN and goes directly to the internet.
        val fd = Builder()
            .setSession("PrivacyGuard")
            .addAddress("10.0.0.2", 32)
            .addDnsServer("10.0.0.1")
            .addRoute("0.0.0.0", 0)
            .setMtu(1500)
            .establish() ?: run { stopSelf(); return }

        tunFd        = fd
        tunInterface = TunInterface(fd.fileDescriptor)
        tunWriter    = TunWriter(tunInterface).also { it.start() }

        // ── DNS handler ───────────────────────────────────────────────────────
        val settings = com.privacyguard.app.data.local.preferences.SettingsPreferences.getInstance(this)
        dnsHandler = DnsHandler(
            filterEngine    = filterEngine,
            tunWriter       = tunWriter,
            anomalyDetector = dnsAnomalyDetector,
            upstreamDns     = settings.upstreamDns.value,
            dohEnabled      = settings.dohEnabled.value,
            dohProvider     = settings.dohProvider.value,
            protectSocket   = { sock -> protect(sock) },
        ).also { h ->
            h.resolvedListener = DnsHandler.ResolvedListener { ip, hostname ->
                sessionTable.allSessions()
                    .filter { it.key.destinationIp == ip && it.resolvedHostname == null }
                    .forEach { it.resolvedHostname = hostname }
                // Backfill hostname into any active connection entries already in StatsManager
                StatsManager.update {
                    copy(activeConnections = activeConnections.map { conn ->
                        if (conn.destinationIp == ip && conn.hostName == null)
                            conn.copy(hostName = hostname)
                        else conn
                    })
                }
            }
            h.anomalyListener = DnsHandler.AnomalyListener { /* already handled in dnsAnomalyDetector */ }
        }

        // ── Reactive DoH setting updates ──────────────────────────────────────
        // Propagate changes from SettingsPreferences to DnsHandler at runtime
        // so toggling DoH or switching provider takes effect without restarting VPN.
        scope.launch { settings.dohEnabled.collect  { dnsHandler.dohEnabled  = it } }
        scope.launch { settings.dohProvider.collect { dnsHandler.dohProvider = it } }

        // ── Forwarders ────────────────────────────────────────────────────────
        tcpForwarder = TcpForwarder(sessionTable, tunWriter, encEnforcer, filterEngine, ::protect).also { it.start() }
        udpForwarder = UdpForwarder(sessionTable, tunWriter, ::protect).also { it.start() }

        // ── TUN reader — hot path ─────────────────────────────────────────────
        tunReader = TunReader(tunInterface).also {
            it.addHandler { ip -> onPacket(ip) }
            it.start()
        }

        registerReceiver(stopReceiver, IntentFilter(NotificationHelper.ACTION_STOP_VPN))
        if (com.privacyguard.app.vpn.KillSwitch.isEnabled()) {
            com.privacyguard.app.vpn.KillSwitch.startMonitoring(this)
        }
        isRunning = true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ★ Packet Processing Hot Path
    // ─────────────────────────────────────────────────────────────────────────

    private fun onPacket(ip: IpPacket) {
        StatsManager.recordPacket(ip.totalLength.toLong())

        // ── IP-level block (no UID needed) ───────────────────────────────────
        if (ipFilter.isBlocked(ip.destinationIp)) {
            recordBlock(); return
        }

        when (ip.protocol) {

            // ── UDP ──────────────────────────────────────────────────────────
            IpPacket.PROTO_UDP -> {
                val udp = UdpPacket.parse(ip) ?: return
                val uid = com.privacyguard.app.vpn.UidMapper.uidForSrcPort(udp.sourcePort, 17)
                val pkg = appTracker.packageForUid(uid)
                if (dnsHandler.handle(ip, udp, pkg)) return
                val decision = filterEngine.evaluate(uid, pkg, null, ip.destinationIp, udp.destinationPort, 17)
                if (decision.isBlocked) { recordBlock(); return }
                udpForwarder.handle(ip, udp, uid, pkg)
            }

            // ── TCP ──────────────────────────────────────────────────────────
            IpPacket.PROTO_TCP -> {
                val tcp = TcpPacket.parse(ip) ?: return
                if (tcp.isSyn) {
                    val uid = com.privacyguard.app.vpn.UidMapper.uidForSrcPort(tcp.sourcePort, 6)
                    val pkg = appTracker.packageForUid(uid)
                    val decision = filterEngine.evaluate(uid, pkg, null, ip.destinationIp, tcp.destinationPort, 6)
                    if (decision.isBlocked) { recordBlock(); return }
                    tcpForwarder.handle(ip, tcp, uid, pkg)
                } else {
                    val uid = com.privacyguard.app.vpn.UidMapper.uidForSrcPort(tcp.sourcePort, 6)
                    val pkg = appTracker.packageForUid(uid)
                    tcpForwarder.handle(ip, tcp, uid, pkg)
                }
            }
        }
    }

    private fun recordBlock() {
        val n = totalBlocked.incrementAndGet()
        StatsManager.incrementBlocked()
        if (n % 25 == 0L) notifHelper.updateVpnNotification(n, getMainActivityClass())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Session Lifecycle → Metadata Engine + DB
    // ─────────────────────────────────────────────────────────────────────────

    private inner class SessionLifecycleListener : SessionTable.Listener {
        override fun onSessionCreated(session: Session) {
            val appLabel = appTracker.labelForPackage(session.ownerPackage ?: "")
                ?: session.ownerPackage ?: "Unknown"
            StatsManager.update {
                copy(activeConnections = activeConnections + ActiveConnectionInfo(
                    id              = session.key.toString(),
                    appName         = appLabel,
                    destination     = "${session.key.destinationIp}:${session.key.destinationPort}",
                    destinationIp   = session.key.destinationIp,
                    destinationPort = session.key.destinationPort,
                    protocol        = session.key.protocolName,
                    isBlocked       = false,
                    bytesTransferred = 0L,
                    hostName        = session.hostname,
                    securityInfo    = session.encryptionStatus.name,
                    encryptionInfo  = session.tlsVersion?.name ?: "",
                ))
            }
        }

        override fun onSessionClosed(session: Session) {
            // Remove from live connections list
            val sessionId = session.key.toString()
            StatsManager.update {
                copy(activeConnections = activeConnections.filter { it.id != sessionId })
            }

            com.privacyguard.app.vpn.UidMapper.evict(
                session.key.sourcePort, session.key.protocol)
            val snapshot = session.snapshot()
            if (snapshot.encryptionStatus == EncryptionStatus.CLEARTEXT) {
                totalCleartext.incrementAndGet()
            }

            scope.launch {
                // ── Pillar 1: Feed MetadataEngine ────────────────────────────
                metadataEngine.record(
                    snapshot     = snapshot,
                    isBackground = snapshot.wasBackground,
                    encStatus    = snapshot.encryptionStatus,
                    tlsVer       = snapshot.tlsVersion,
                    sni          = snapshot.tlsSni,
                )

                val appLabel = appTracker.labelForPackage(snapshot.ownerPackage ?: "")
                    ?: snapshot.ownerPackage ?: "Unknown"

                // ── Persist connection record (BRD §8.1) ────────────────────
                val dstIp   = snapshot.key.destinationIp
                val isIpv6  = dstIp.contains(':')
                connectionRepo.save(ConnectionEntity(
                    appUid          = snapshot.ownerUid,
                    appName         = appLabel,
                    packageName     = snapshot.ownerPackage ?: "",
                    domain          = snapshot.hostname,
                    destinationIp   = dstIp,
                    destinationPort = snapshot.key.destinationPort,
                    destinationIpv6 = if (isIpv6) dstIp else null,
                    isIPv6          = isIpv6,
                    sniHostname     = snapshot.tlsSni,
                    protocol        = snapshot.key.protocolName,
                    bytesSent       = snapshot.bytesFromDevice,
                    bytesReceived   = snapshot.bytesToDevice,
                    wasBlocked      = false,
                    timestamp       = snapshot.createdAt,
                    durationMs      = snapshot.ageMs,
                    encryptionStatus = snapshot.encryptionStatus.name,
                    tlsVersion      = snapshot.tlsVersion?.name,
                    wasBackground   = snapshot.wasBackground,
                ))

                // ── Persist updated profile every 10 sessions ────────────────
                if (metadataEngine.totalRecorded.get() % 10 == 0L) {
                    val pkg = snapshot.ownerPackage ?: return@launch
                    val profiles = metadataEngine.profilesForApp(pkg)
                    metadataRepo.saveAll(profiles)
                }

                // ── Push to StatsManager (feeds dashboard UI) ────────────────
                val hostname = snapshot.hostname ?: snapshot.key.destinationIp
                val sessionBytes = snapshot.bytesFromDevice + snapshot.bytesToDevice
                val pkg = snapshot.ownerPackage ?: ""
                StatsManager.update {
                    val existing = appStats.find { it.packageName == pkg }
                    val updatedStat = existing?.copy(
                        bytesTransferred = existing.bytesTransferred + sessionBytes,
                    ) ?: AppStat(
                        uid              = snapshot.ownerUid,
                        packageName      = pkg,
                        appName          = appLabel,
                        blockedCount     = 0L,
                        bytesTransferred = sessionBytes,
                    )
                    copy(
                        recentActivity = (listOf(ActivityInfo(
                            appName     = appLabel,
                            description = hostname,
                            timeMillis  = snapshot.createdAt,
                            isBlocked   = false,
                        )) + recentActivity).take(50),
                        appStats = (appStats.filter { it.packageName != pkg } + updatedStat)
                            .sortedByDescending { it.bytesTransferred }
                            .take(50),
                    )
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stop
    // ─────────────────────────────────────────────────────────────────────────

    private fun stopVpn() {
        if (!isRunning) return
        isRunning = false
        com.privacyguard.app.vpn.BootReceiver.markVpnStopped(this)
        runCatching { unregisterReceiver(stopReceiver) }

        // Flush remaining profiles to DB before stopping
        scope.launch {
            metadataRepo.saveAll(metadataEngine.allProfiles())
            val retentionDays = com.privacyguard.app.data.local.preferences.SettingsPreferences
                .getInstance(applicationContext)
                .retentionDays
                .value
            connectionRepo.pruneOldRecords(retentionDays)
            metadataRepo.pruneOld(retentionDays.toLong() * 86_400_000L)
            dnsAnomalyRepo.pruneOld(retentionDays)
        }

        com.privacyguard.app.vpn.KillSwitch.stopMonitoring()
        tunReader.stop()
        tunWriter.stop()
        tcpForwarder.stop()
        udpForwarder.stop()
        sessionTable.clear()
        com.privacyguard.app.vpn.UidMapper.clear()
        StatsManager.reset()
        runCatching { tunFd?.close() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NotificationHelper.ACTION_STOP_VPN) stopVpn()
        }
    }

    private fun getMainActivityClass(): Class<*> = try {
        Class.forName("com.privacyguard.app.MainActivity")
    } catch (_: ClassNotFoundException) {
        PrivacyVpnService::class.java
    }

    private fun buildDatabase(): AppDatabase =
        Room.databaseBuilder(
            this, AppDatabase::class.java,
            AppDatabase.DATABASE_NAME,
        ).fallbackToDestructiveMigration(dropAllTables = true).build()

    companion object {
        const val ACTION_STOP = "com.privacyguard.action.STOP_VPN"

        @Volatile var isRunning = false
            private set

        fun startIntent(ctx: Context) = Intent(ctx, PrivacyVpnService::class.java)
        fun stopIntent(ctx: Context)  = Intent(ctx, PrivacyVpnService::class.java).also { it.action = ACTION_STOP }
    }
}
