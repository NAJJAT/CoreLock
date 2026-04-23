package com.privacyguard.platform.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterRule
import com.privacyguard.core.metadata.EncryptionStatus
import com.privacyguard.core.metadata.MetadataEngine
import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.packet.TcpPacket
import com.privacyguard.core.packet.UdpPacket
import com.privacyguard.core.session.Session
import com.privacyguard.core.session.SessionTable
import com.privacyguard.data.repository.*
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
        notifHelper = NotificationHelper(this).also { it.createChannels() }
        startForeground(
            NotificationHelper.NOTIFICATION_ID_VPN,
            notifHelper.buildVpnActiveNotification(0, getMainActivityClass()),
        )

        val db = buildDatabase()

        // ── Repositories ──────────────────────────────────────────────────────
        connectionRepo = ConnectionRepo(db.connectionDao())
        rulesRepo      = RulesRepo(db.rulesDao(), FilterEngine())
        blocklistRepo  = BlocklistRepo(db.blocklistDao())
        metadataRepo   = MetadataRepo(db.connectionProfileDao())
        dnsAnomalyRepo = DnsAnomalyRepo(db.dnsAnomalyDao())

        // ── Pillar 3: Blocking setup ──────────────────────────────────────────
        filterEngine  = FilterEngine()
        domainFilter  = DomainFilter()
        ipFilter      = IpFilter()
        appFilter     = AppFilter { uid -> packageManager.getNameForUid(uid) }
        appTracker    = AppTracker(this, appFilter)

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
        }

        // ── Session table ────────────────────────────────────────────────────
        sessionTable = SessionTable()
        sessionTable.startReaper()
        sessionTable.addListener(SessionLifecycleListener())

        // ── TUN interface ─────────────────────────────────────────────────────
        val fd = Builder()
            .setSession("PrivacyGuard")
            .addAddress("10.0.0.2", 32)
            .addDnsServer("10.0.0.1")
            .addRoute("0.0.0.0", 0)
            .setMtu(TunInterface.DEFAULT_MTU)
            .establish() ?: run { stopSelf(); return }

        tunFd        = fd
        tunInterface = TunInterface(fd.fileDescriptor)
        tunWriter    = TunWriter(tunInterface).also { it.start() }

        // ── DNS handler ───────────────────────────────────────────────────────
        dnsHandler = DnsHandler(filterEngine, tunWriter, dnsAnomalyDetector).also { h ->
            h.resolvedListener = DnsHandler.ResolvedListener { ip, hostname ->
                sessionTable.allSessions()
                    .filter { it.key.destinationIp == ip && it.resolvedHostname == null }
                    .forEach { it.resolvedHostname = hostname }
            }
            h.anomalyListener = DnsHandler.AnomalyListener { /* already handled in dnsAnomalyDetector */ }
        }

        // ── Forwarders ────────────────────────────────────────────────────────
        tcpForwarder = TcpForwarder(sessionTable, tunWriter, encEnforcer, filterEngine, ::protect).also { it.start() }
        udpForwarder = UdpForwarder(sessionTable, tunWriter, ::protect).also { it.start() }

        // ── TUN reader — hot path ─────────────────────────────────────────────
        tunReader = TunReader(tunInterface).also {
            it.addHandler { ip -> onPacket(ip) }
            it.start()
        }

        registerReceiver(stopReceiver, IntentFilter(NotificationHelper.ACTION_STOP_VPN))
        isRunning = true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ★ Packet Processing Hot Path
    // ─────────────────────────────────────────────────────────────────────────

    private fun onPacket(ip: IpPacket) {
        // ── IP-level block ───────────────────────────────────────────────────
        if (ipFilter.isBlocked(ip.destinationIp)) {
            totalBlocked.incrementAndGet(); return
        }

        val uid = -1  // Android API 29+: getConnectionOwnerUid() — add later
        val pkg = appTracker.packageForUid(uid)

        when (ip.protocol) {

            // ── UDP ──────────────────────────────────────────────────────────
            IpPacket.PROTO_UDP -> {
                val udp = UdpPacket.parse(ip) ?: return
                if (dnsHandler.handle(ip, udp, pkg)) return

                val decision = filterEngine.evaluate(uid, pkg, null, ip.destinationIp, udp.destinationPort, 17)
                if (decision.isBlocked) { totalBlocked.incrementAndGet(); return }
                udpForwarder.handle(ip, udp)
            }

            // ── TCP ──────────────────────────────────────────────────────────
            IpPacket.PROTO_TCP -> {
                val tcp = TcpPacket.parse(ip) ?: return

                // Evaluate blocking rules on new connections only (SYN)
                if (tcp.isSyn) {
                    val decision = filterEngine.evaluate(uid, pkg, null, ip.destinationIp, tcp.destinationPort, 6)
                    if (decision.isBlocked) { totalBlocked.incrementAndGet(); return }
                }

                // Encryption enforcement happens inside TcpForwarder on first data packet
                tcpForwarder.handle(ip, tcp)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Session Lifecycle → Metadata Engine + DB
    // ─────────────────────────────────────────────────────────────────────────

    private inner class SessionLifecycleListener : SessionTable.Listener {
        override fun onSessionCreated(session: Session) { /* reserved for live UI push */ }

        override fun onSessionClosed(session: Session) {
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

                // ── Persist connection record ────────────────────────────────
                connectionRepo.save(snapshot, wasBlocked = false, matchedRuleId = null)

                // ── Persist updated profile every 10 sessions ────────────────
                if (metadataEngine.totalRecorded.get() % 10 == 0L) {
                    val pkg = snapshot.ownerPackage ?: return@launch
                    val profiles = metadataEngine.profilesForApp(pkg)
                    metadataRepo.upsertAll(profiles)
                }

                // ── Update notification ──────────────────────────────────────
                if (totalBlocked.get() % 25 == 0L) {
                    notifHelper.updateVpnNotification(totalBlocked.get(), getMainActivityClass())
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
        runCatching { unregisterReceiver(stopReceiver) }

        // Flush remaining profiles to DB before stopping
        scope.launch {
            metadataRepo.upsertAll(metadataEngine.allProfiles())
            connectionRepo.pruneOldRecords()
            dnsAnomalyRepo.pruneOld()
        }

        tunReader.stop()
        tunWriter.stop()
        tcpForwarder.stop()
        udpForwarder.stop()
        sessionTable.clear()
        runCatching { tunFd?.close() }
        stopForeground(true)
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
        Class.forName("com.privacyguard.ui.MainActivity")
    } catch (_: ClassNotFoundException) {
        PrivacyVpnService::class.java
    }

    /** Room database — replace with actual Room builder in real code. */
    private fun buildDatabase(): com.privacyguard.data.db.AppDatabase =
        androidx.room.Room.databaseBuilder(
            this, com.privacyguard.data.db.AppDatabase::class.java,
            com.privacyguard.data.db.AppDatabase.DATABASE_NAME,
        ).build()

    companion object {
        const val ACTION_STOP = "com.privacyguard.action.STOP_VPN"

        @Volatile var isRunning = false
            private set

        fun startIntent(ctx: Context) = Intent(ctx, PrivacyVpnService::class.java)
        fun stopIntent(ctx: Context)  = Intent(ctx, PrivacyVpnService::class.java).also { it.action = ACTION_STOP }
    }
}