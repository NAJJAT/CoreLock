package com.privacyguard.platform.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.system.OsConstants
import android.util.Log
import com.privacyguard.app.MainActivity
import com.privacyguard.app.PrivacyGuardApplication
import com.privacyguard.app.core.blocklist.CuratedAdDomains
import com.privacyguard.app.core.stats.ActiveConnectionInfo
import com.privacyguard.app.core.stats.ActivityInfo
import com.privacyguard.app.core.stats.AppStat
import com.privacyguard.app.core.stats.StatsManager
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConfigDatabase
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.db.ConnectionLogger
import com.privacyguard.app.data.db.DnsQueryEntity
import com.privacyguard.app.data.db.TlsAlertEntity
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import com.privacyguard.app.data.repository.BlocklistRepo
import com.privacyguard.app.data.repository.BlocklistSyncBus
import com.privacyguard.app.data.repository.DnsAnomalyRepo
import com.privacyguard.app.data.repository.MetadataRepo
import com.privacyguard.app.data.repository.RuleSyncBus
import com.privacyguard.app.data.repository.RulesRepo
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterRule
import com.privacyguard.core.metadata.EncryptionStatus
import com.privacyguard.core.metadata.MetadataEngine
import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.packet.Ipv6Packet
import com.privacyguard.core.packet.TcpPacket
import com.privacyguard.core.packet.UdpPacket
import com.privacyguard.core.session.Session
import com.privacyguard.core.session.SessionKey
import com.privacyguard.core.session.SessionTable
import com.privacyguard.core.tls.CipherRisk
import com.privacyguard.core.tls.CipherSuiteAnalyzer
import com.privacyguard.core.tls.ClientHelloParser
import com.privacyguard.core.tls.CtMonitor
import com.privacyguard.app.core.blocklist.BlocklistCache
import com.privacyguard.core.tls.Ja3Fingerprinter
import com.privacyguard.vpn.firewall.AppFilter
import com.privacyguard.vpn.firewall.DnsBypassGuard
import com.privacyguard.vpn.firewall.DomainFilter
import com.privacyguard.vpn.firewall.IpFilter
import com.privacyguard.vpn.forwarder.DnsHandler
import com.privacyguard.vpn.forwarder.TcpForwarder
import com.privacyguard.vpn.forwarder.UdpForwarder
import com.privacyguard.vpn.inspector.DnsAnomalyDetector
import com.privacyguard.vpn.inspector.EncryptionEnforcer
import com.privacyguard.vpn.interception.TlsInterception
import com.privacyguard.vpn.interception.TlsInterceptionFactory
import com.privacyguard.vpn.tunnel.TunInterface
import com.privacyguard.vpn.tunnel.TunReader
import com.privacyguard.vpn.tunnel.TunWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The local VPN: owns the TUN interface and wires packets from it through the
 * filters to the TCP/UDP forwarders (IPv4 and IPv6 alike).
 *
 * Startup runs off the main thread (database open, rule and blocklist load, and
 * in enterprise builds the CA) and the tunnel is only established once every
 * filter is loaded, so there is no window in which traffic flows unfiltered.
 */
class PrivacyVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** History writes that must complete even while this service shuts down. */
    private val persistScope = PrivacyGuardApplication.appScope

    private lateinit var metadataEngine: MetadataEngine
    private lateinit var dnsHandler: DnsHandler
    private lateinit var dnsAnomalyDetector: DnsAnomalyDetector
    private lateinit var filterEngine: FilterEngine
    private lateinit var domainFilter: DomainFilter
    // Malware and phishing hosts, at every level: a plain Allow rule does not open them.
    private val securityFilter = DomainFilter(useNativeBloom = false)
    private lateinit var ipFilter: IpFilter
    private lateinit var appFilter: AppFilter
    private lateinit var encEnforcer: EncryptionEnforcer
    private lateinit var sessionTable: SessionTable
    private lateinit var appTracker: AppTracker
    private lateinit var notifHelper: NotificationHelper
    private lateinit var tunInterface: TunInterface
    private lateinit var tunReader: TunReader
    private lateinit var tunWriter: TunWriter
    private lateinit var tcpForwarder: TcpForwarder
    private lateinit var udpForwarder: UdpForwarder
    private lateinit var connectionLogger: ConnectionLogger
    private lateinit var rulesRepo: RulesRepo
    private lateinit var blocklistRepo: BlocklistRepo
    private lateinit var metadataRepo: MetadataRepo
    private lateinit var dnsAnomalyRepo: DnsAnomalyRepo
    private lateinit var interception: TlsInterception

    // Read per UDP packet; SettingsPreferences is a singleton holding the values in memory.
    private fun dualVpnSettings() = SettingsPreferences.getInstance(this).getDualVpnConfig()
    private lateinit var ctMonitor: CtMonitor
    private lateinit var tlsFingerprintMonitor: com.privacyguard.app.vpn.TlsFingerprintMonitor
    private lateinit var sensorWatcher: com.privacyguard.app.adprivacy.SensorUsageWatcher

    private var tunFd: ParcelFileDescriptor? = null
    private val totalBlocked = AtomicLong(0)
    private val totalCleartext = AtomicLong(0)

    // Touched from the TUN reader, forwarder, reaper and IO threads alike.
    private val ja3NotifiedHashes = ConcurrentHashMap.newKeySet<String>()
    private val cleartextNotifiedPackages = ConcurrentHashMap.newKeySet<String>()
    private val backgroundBlockNotifiedPackages = ConcurrentHashMap.newKeySet<String>()
    private val ctNotifiedDomains = ConcurrentHashMap.newKeySet<String>()

    // "Clear data" in Settings: drop what this run remembers, or it is saved back.
    private val dataClearListener = com.privacyguard.app.data.repository.LocalDataEraser.Listener {
        if (::metadataEngine.isInitialized) metadataEngine.clear()
        if (::tlsFingerprintMonitor.isInitialized) tlsFingerprintMonitor.clearCache()
        cleartextNotifiedPackages.clear()
        ctNotifiedDomains.clear()
        totalCleartext.set(0)
        StatsManager.reset()
    }
    @Volatile private var blockCleartextRuleActive = false

    /** Guards the tunnel's lifetime: establishing it vs. tearing it down. */
    private val lifecycleLock = Any()
    @Volatile private var startJob: Job? = null
    @Volatile private var stopRequested = false
    @Volatile private var receiverRegistered = false

    companion object {
        private const val TAG = "PrivacyVpnService"
        /** Virtual resolver inside the tunnel subnet; see DnsHandler. */
        private const val TUNNEL_DNS_V4 = "10.0.0.1"
        const val ACTION_STOP = "com.privacyguard.action.STOP_VPN"
        private const val DNS_ATTRIBUTION_WINDOW_MS = 60_000L
        /** How often a live connection is re-checked against the rules. */
        private const val FILTER_RECHECK_MS = 1_000L
        /** Retries of one blocked attempt within this window are logged once. */
        private const val BLOCK_DEDUP_MS = 30_000L
        /** Same app, name and verdict within this window is the same lookup. */
        private const val DNS_REPEAT_MS = 10_000L
        private const val PROFILE_SAVE_MS = 60_000L

        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

        @Volatile var isRunning = false
            private set

        @Volatile private var running: PrivacyVpnService? = null

        /**
         * Diagnostics: a real lookup over the VPN's own upstream path. The app's own
         * sockets go through the tunnel, where port 53 to outside resolvers is dropped
         * on purpose, so only the service can test this. Null when protection is off.
         */
        fun probeUpstreamDns(): Boolean? = running?.takeIf { it::dnsHandler.isInitialized }?.dnsHandler?.probeUpstream()

        fun startIntent(ctx: Context) = Intent(ctx, PrivacyVpnService::class.java)
        fun stopIntent(ctx: Context) = Intent(ctx, PrivacyVpnService::class.java).also { it.action = ACTION_STOP }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VPN service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        if (intent?.action == ACTION_STOP) { stopVpn(); return START_NOT_STICKY }
        if (!isRunning && startJob?.isActive != true) startVpn()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "VPN service destroyed")
        com.privacyguard.app.data.repository.LocalDataEraser.unregister(dataClearListener)
        stopVpn()
        scope.cancel()
        super.onDestroy()
    }

    private fun startVpn() {
        Log.i(TAG, "startVpn() — building components")
        stopRequested = false
        // Foreground at once (we were started with startForegroundService); the
        // slow part runs in the background.
        notifHelper = NotificationHelper(this).also { it.createChannels() }
        startForeground(
            NotificationHelper.NOTIFICATION_ID_VPN,
            notifHelper.buildVpnActiveNotification(0, MainActivity::class.java),
        )
        startJob = scope.launch {
            try {
                startVpnInternal()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "FATAL: startVpn crashed", e)
                runCatching { releaseResources() }
            }
        }
    }

    private suspend fun startVpnInternal() {
        // VPN permission revoked (another VPN app took over): establish() cannot
        // work, and boot must stop trying.
        if (prepare(this) != null) {
            Log.w(TAG, "VPN permission not granted; not starting")
            com.privacyguard.app.vpn.BootReceiver.markVpnStopped(this)
            releaseResources()
            return
        }

        val db = buildDatabase()
        val settings = SettingsPreferences.getInstance(this)

        filterEngine = FilterEngine()
        domainFilter = DomainFilter()
        ipFilter = IpFilter()
        appFilter = AppFilter { uid -> packageManager.getNameForUid(uid) }
        appTracker = AppTracker(this, appFilter)

        connectionLogger = ConnectionLogger.getInstance(db.connectionDao())
        rulesRepo = RulesRepo(configDatabase().rulesDao(), filterEngine)
        blocklistRepo = BlocklistRepo(db.blocklistDao(), configDatabase().blocklistToggleDao())
        metadataRepo = MetadataRepo(db.connectionProfileDao())
        dnsAnomalyRepo = DnsAnomalyRepo(db.dnsAnomalyDao())
        metadataEngine = MetadataEngine()
        // Saved profiles first: counts accumulate across VPN restarts.
        runCatching { metadataEngine.seed(metadataRepo.recent()) }
        encEnforcer = EncryptionEnforcer()

        // ── Filters first: the tunnel must never carry traffic they have not seen ──
        rulesRepo.loadIntoEngine()
        blockCleartextRuleActive = hasBlockCleartextRule()
        filterEngine.blockLevel = parseBlockLevel(settings.protectionLevel.value)
        blocklistVersionLoaded = BlocklistSyncBus.version.value
        // The last loaded lists, from a plain file, take a second or two; reading them
        // from the encrypted database takes about half a minute on a phone. The
        // database is read right after, in case the lists changed while protection was off.
        val cached = runCatching { BlocklistCache.read(this, filterEngine.blockLevel.name) }.getOrNull()
        if (cached != null) {
            domainFilter.rebuild(cached.first)
            securityFilter.rebuild(cached.second)
            Log.i(TAG, "Blocklists from cache: ${domainFilter.size} names")
            scope.launch { reloadBlocklists() }
        } else {
            reloadBlocklists()
        }
        currentCoroutineContext().ensureActive()

        dnsAnomalyDetector = DnsAnomalyDetector()
        dnsAnomalyDetector.anomalyListener = DnsAnomalyDetector.AnomalyListener { anomaly ->
            scope.launch {
                val stored = dnsAnomalyRepo.save(anomaly, com.privacyguard.app.core.network.NetworkIdentity.current(applicationContext).key)
                // Notify only for what was stored: the same app and host at most once a day.
                if (stored && anomaly.severity >= 8) {
                    notifHelper.postSuspiciousAlert(
                        appName = appTracker.labelForPackage(anomaly.packageName) ?: anomaly.packageName,
                        domain = anomaly.domain,
                        mainActivityClass = MainActivity::class.java,
                    )
                }
            }
        }

        sessionTable = SessionTable()
        sessionTable.addListener(SessionLifecycleListener())

        ctMonitor = CtMonitor(baseline = com.privacyguard.core.tls.SharedPrefsCtBaselineStore(this)) { domain, entries ->
            val detail = entries.firstOrNull()?.let { "issuer: ${it.issuerName.take(60)}" }
            scope.launch {
                db.tlsAlertDao().insert(TlsAlertEntity(
                    timestamp = System.currentTimeMillis(),
                    alertType = "CT_NEW_CERT",
                    hash = null,
                    severity = 4,
                    sni = domain,
                    detail = detail,
                ))
                if (ctNotifiedDomains.add(domain)) {
                    notifHelper.postCtCertAlert(domain, entries.size, MainActivity::class.java)
                }
            }
        }
        // HTTPS inspection exists only in enterprise builds; consumer gets a no-op.
        interception = TlsInterceptionFactory.create(this, db, ::protect)
        tlsFingerprintMonitor = com.privacyguard.app.vpn.TlsFingerprintMonitor(
            context = this,
            db = db,
            scope = scope,
            onAlert = { label, pkg, sni -> notifHelper.postTlsFingerprintChange(label, pkg, sni, MainActivity::class.java) },
            labelFor = { uid, pkg -> resolvedAppLabel(uid, pkg) },
        )
        currentCoroutineContext().ensureActive()

        synchronized(lifecycleLock) {
            if (stopRequested) return
            establishTunnel(db, settings) ?: return
            isRunning = true
            running = this
            _isRunningFlow.value = true
        }
        com.privacyguard.app.vpn.BootReceiver.markVpnActive(this)
        watchSettings(settings)
        com.privacyguard.app.data.repository.LocalDataEraser.register(dataClearListener)
        startLiveStats()
        scope.launch {
            appTracker.preloadInstalledApps()
            com.privacyguard.app.core.tracker.ExodusUpdater.initialize(applicationContext)
            com.privacyguard.app.core.tracker.ExodusUpdater.refresh(applicationContext)
        }
        Log.i(TAG, "VPN fully started — all pillars active")
    }

    /**
     * Opens the TUN interface and starts every thread that serves it. Returns null
     * (after cleaning up) if the system refused the tunnel. Caller holds [lifecycleLock].
     */
    private fun establishTunnel(db: AppDatabase, settings: SettingsPreferences): ParcelFileDescriptor? {
        Log.i(TAG, "Calling Builder().establish() — requesting TUN fd")
        val fd = Builder()
            .setSession("PrivacyGuard")
            .addAddress("10.0.0.2", 24)
            .addAddress("fd00:1:fd00:1::2", 64)
            // The tunnel's own address: DnsHandler answers every UDP/53 query and
            // forwards it to the configured upstream, so apps never learn (or
            // prefer) a public resolver they could reach around the blocklist.
            .addDnsServer(TUNNEL_DNS_V4)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .setMtu(UdpForwarder.TUN_MTU)
            .establish() ?: run {
            Log.e(TAG, "establish() returned null — VPN permission not granted")
            com.privacyguard.app.vpn.BootReceiver.markVpnStopped(this)
            releaseResourcesLocked()
            return null
        }
        Log.i(TAG, "TUN fd established")

        tunFd = fd
        tunInterface = TunInterface(fd.fileDescriptor)
        tunWriter = TunWriter(tunInterface).also { it.start() }
        failIfDebugHookArmed()

        dnsHandler = DnsHandler(
            filterEngine = filterEngine,
            tunWriter = tunWriter,
            anomalyDetector = dnsAnomalyDetector,
            upstreamDns = validUpstream(settings.upstreamDns.value),
            dohEnabled = settings.dohEnabled.value,
            dohProvider = settings.dohProvider.value,
            protectSocket = { sock -> protect(sock) },
            protectTcpSocket = { sock -> protect(sock) },
            domainFilter = domainFilter,
            securityFilter = securityFilter,
            adSdkPolicy = com.privacyguard.app.adprivacy.AdSdkPolicy(enabled = { settings.adSdkBlockingEnabled.value }),
            dualVpnConfig = { settings.getDualVpnConfig() },
        ).also { h ->
            h.resolvedListener = DnsHandler.ResolvedListener { ip, hostname -> onDnsResolved(ip, hostname) }
            // Signs about the network itself: stored like DNS anomalies, owned by "network".
            h.networkSignalListener = DnsHandler.NetworkSignalListener { type, domain, detail -> reportNetworkSignal(type, domain, detail) }
            h.queryListener = DnsHandler.QueryListener { ownerPackage, domain, wasBlocked ->
                val pkg = ownerPackage?.takeIf { it.isNotBlank() } ?: "Unknown"
                // One row per lookup: A + AAAA (+ HTTPS) and retries of it are one entry.
                if (dnsRepeats.isRepeat("$pkg|${domain.lowercase()}|$wasBlocked", System.currentTimeMillis())) return@QueryListener
                val appName = if (pkg != "Unknown") appTracker.labelForPackage(pkg).orEmpty() else "Unknown"
                val idle = isPhoneIdle()
                val foreground = if (pkg != "Unknown") foregroundDetector.stateOf(pkg) else null
                persistScope.launch {
                    db.dnsQueryDao().insert(DnsQueryEntity(
                        timestamp = System.currentTimeMillis(),
                        appPackage = pkg,
                        appName = appName,
                        domain = domain,
                        wasBlocked = wasBlocked,
                        phoneWasIdle = idle,
                        appForeground = foreground,
                    ))
                }
            }
        }

        sensorWatcher = com.privacyguard.app.adprivacy.SensorUsageWatcher(
            context = this,
            dao = db.sensorEventDao(),
            scope = scope,
            foreground = foregroundDetector,
        ).also { it.start() }

        sessionTable.startReaper()
        tcpForwarder = TcpForwarder(
            sessionTable, tunWriter, encEnforcer, filterEngine, ::protect,
            interception,
            dualVpnConfig = { settings.getDualVpnConfig() },
            onClientHello = { session, bytes ->
                inspectTlsClientHello(bytes, session.ownerUid, session.ownerPackage ?: appTracker.packageForUid(session.ownerUid))
            },
        ).also { it.start() }
        udpForwarder = UdpForwarder(sessionTable, tunWriter, ::protect).also { it.start() }

        tunReader = TunReader(tunInterface).also {
            it.addHandler { ip -> onPacket(ip) }
            it.addIpv6Handler { raw -> onIpv6Packet(raw) }
            it.start()
        }
        Log.i(TAG, "Forwarders and TunReader started")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, IntentFilter(NotificationHelper.ACTION_STOP_VPN), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stopReceiver, IntentFilter(NotificationHelper.ACTION_STOP_VPN))
        }
        receiverRegistered = true

        com.privacyguard.app.vpn.KillSwitch.updateLockdownState(this)
        if (com.privacyguard.app.vpn.KillSwitch.isEnabled()) {
            com.privacyguard.app.vpn.KillSwitch.startMonitoring(this)
        }
        return fd
    }

    /** Applies setting and rule changes to the running tunnel. */
    private fun watchSettings(settings: SettingsPreferences) {
        scope.launch {
            RuleSyncBus.version.collect {
                rulesRepo.loadIntoEngine()
                blockCleartextRuleActive = hasBlockCleartextRule()
            }
        }
        // The version current at startup was loaded before the tunnel came up; only
        // newer ones need another load (each one reads every list again).
        scope.launch {
            BlocklistSyncBus.version.collect { v ->
                if (v != blocklistVersionLoaded) { blocklistVersionLoaded = v; reloadBlocklists() }
            }
        }
        // Opt-in: CT checks send contacted domains to crt.sh. Turning it off stops at once.
        scope.launch { settings.ctMonitoringEnabled.collect { ctMonitor.setEnabled(it) } }
        scope.launch { settings.dohEnabled.collect { dnsHandler.dohEnabled = it } }
        scope.launch { settings.dohProvider.collect { dnsHandler.dohProvider = it } }
        scope.launch { settings.upstreamDns.collect { dnsHandler.upstreamDns = validUpstream(it) } }
        scope.launch {
            settings.protectionLevel.collect {
                val level = parseBlockLevel(it)
                if (level != filterEngine.blockLevel) {
                    filterEngine.blockLevel = level
                    reloadBlocklists()
                }
            }
        }
    }

    /**
     * The DNS filter blocks only the categories of the current protection level
     * (see [com.privacyguard.core.filter.ProtectionLevels]). Risk scoring still
     * knows every listed tracker: knowing a host is a tracker is not blocking it.
     */
    private val blocklistReloadLock = kotlinx.coroutines.sync.Mutex()
    @Volatile private var blocklistVersionLoaded = -1L

    private suspend fun reloadBlocklists() = blocklistReloadLock.withLock {
        val level = filterEngine.blockLevel
        val blocked = blocklistRepo.domainsIn(com.privacyguard.core.filter.ProtectionLevels.categoriesFor(level)) +
            (if (com.privacyguard.core.filter.ProtectionLevels.includesCuratedAdHosts(level)) CuratedAdDomains.all else emptyList())
        val security = blocklistRepo.domainsIn(setOf(com.privacyguard.app.data.repository.BlocklistRepo.CAT_MALWARE))
        domainFilter.rebuild(blocked)
        securityFilter.rebuild(security)
        runCatching { BlocklistCache.write(this, level.name, blocked, security) }
        Log.i(TAG, "Blocklists loaded: ${domainFilter.size} names at $level")
        // Risk scoring only, not blocking: load it after the tunnel is up. At startup
        // this read of every list held the tunnel back for minutes.
        scope.launch(Dispatchers.IO) {
            runCatching { metadataEngine.knownTrackers = (blocklistRepo.allDomains() + CuratedAdDomains.all).toSet() }
        }
    }

    private suspend fun hasBlockCleartextRule(): Boolean =
        configDatabase().rulesDao().getAllRules().any {
            it.enabled &&
                it.action == FilterRule.Action.DENY.name &&
                it.matchEncryption == EncryptionStatus.CLEARTEXT.name
        }

    private fun parseBlockLevel(level: String): FilterEngine.BlockLevel =
        com.privacyguard.core.filter.ProtectionLevels.parse(level)

    /** A stored upstream that is not an IP literal would need a DNS lookup through this very tunnel. */
    private fun validUpstream(value: String): String =
        value.trim().takeIf { DnsHandler.isIpLiteral(it) } ?: DnsHandler.DEFAULT_UPSTREAM_DNS

    /**
     * Debug builds only (DebugHooksReceiver FAIL_NEXT_START): fails once, right after
     * the tunnel is up, the worst moment, to prove a half-started VPN is torn down.
     * BuildConfig.DEBUG is a compile-time false in release, so R8 removes this.
     */
    private fun failIfDebugHookArmed() {
        if (!com.privacyguard.app.BuildConfig.DEBUG) return
        val prefs = getSharedPreferences("debug_hooks", MODE_PRIVATE)
        if (!prefs.getBoolean("fail_next_start", false)) return
        prefs.edit().putBoolean("fail_next_start", false).commit()
        throw IllegalStateException("Debug hook: simulated startup failure")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Packet path (TUN reader thread)
    // ─────────────────────────────────────────────────────────────────────────

    private fun onPacket(ip: IpPacket) {
        StatsManager.recordPacket(ip.totalLength.toLong())

        if (ipFilter.isBlocked(ip.destinationIp)) {
            recordBlock()
            return
        }

        when (ip.protocol) {
            IpPacket.PROTO_UDP -> {
                val udp = UdpPacket.parse(ip) ?: return
                onUdp(ip.sourceIp, ip.destinationIp, udp.sourcePort, udp.destinationPort, udp.data, ip to udp)
            }
            IpPacket.PROTO_TCP -> {
                val tcp = TcpPacket.parse(ip) ?: return
                onTcp(ip.sourceIp, ip.destinationIp, tcp)
            }
        }
    }

    /**
     * IPv6 TCP and UDP go through the same forwarders as IPv4 (flow control, TLS
     * fingerprinting, inspection and history). Other next headers, including
     * extension headers, are dropped.
     */
    private fun onIpv6Packet(raw: ByteArray) {
        val ipv6 = Ipv6Packet.parse(raw)
        if (ipv6 == null) {
            StatsManager.recordIpv6Blocked(raw.size.toLong())
            return
        }
        StatsManager.recordPacket(raw.size.toLong())
        val off = Ipv6Packet.HEADER_LEN
        val end = minOf(raw.size, off + ipv6.payloadLength)
        when (ipv6.nextHeader) {
            Ipv6Packet.PROTO_TCP -> {
                val tcp = TcpPacket.parse(raw, off, end) ?: return
                onTcp(ipv6.sourceIp, ipv6.destinationIp, tcp)
            }
            Ipv6Packet.PROTO_UDP -> {
                if (end < off + 8) return
                val srcPort = ((raw[off].toInt() and 0xFF) shl 8) or (raw[off + 1].toInt() and 0xFF)
                val dstPort = ((raw[off + 2].toInt() and 0xFF) shl 8) or (raw[off + 3].toInt() and 0xFF)
                val udpLen = ((raw[off + 4].toInt() and 0xFF) shl 8) or (raw[off + 5].toInt() and 0xFF)
                if (udpLen < 8) return
                val data = raw.copyOfRange(off + 8, minOf(off + udpLen, end))
                onUdp(ipv6.sourceIp, ipv6.destinationIp, srcPort, dstPort, data, null)
            }
            else -> StatsManager.recordIpv6Blocked(raw.size.toLong())
        }
    }

    private fun onTcp(srcIp: String, dstIp: String, tcp: TcpPacket) {
        val dstPort = tcp.destinationPort
        if (DnsBypassGuard.isEncryptedDnsPort(dstPort) ||
            DnsBypassGuard.isUnfilteredPlainDns(dstPort) ||
            DnsBypassGuard.isDohAddress(dstIp, dstPort)) {
            // Dropping the SYN makes the app fall back to plain DNS through the tunnel.
            recordBlock()
            if (tcp.isSyn) logBlockedAttempt(SessionKey.of(srcIp, tcp.sourcePort, dstIp, dstPort, IpPacket.PROTO_TCP))
            return
        }
        val key = SessionKey.of(srcIp, tcp.sourcePort, dstIp, dstPort, IpPacket.PROTO_TCP)
        val existing = sessionTable.get(key)
        if (existing != null && !tcp.isSyn) {
            if (blockedOnRecheck(existing)) {
                tcpForwarder.reset(key)
                return
            }
            tcpForwarder.handle(key, tcp, existing.ownerUid, existing.ownerPackage)
            return
        }
        if (!tcp.isSyn) {
            // No session and not a SYN: the forwarder answers with a reset.
            tcpForwarder.handle(key, tcp)
            return
        }

        // A new connection: the owner is looked up once, here, not per packet.
        val uid = resolveOwnerUid(OsConstants.IPPROTO_TCP, key)
        val pkg = appTracker.packageForUid(uid)
        val decision = filterEngine.evaluate(uid, pkg, null, dstIp, dstPort, IpPacket.PROTO_TCP,
            isBackground = isKnownBackground(pkg))
        if (decision.isBlocked) {
            onBlocked(decision, pkg, dstIp)
            logBlockedAttempt(key, uid, pkg)
            return
        }
        tcpForwarder.handle(key, tcp, uid, pkg)
        sessionTable.get(key)?.let { onSessionOpened(it, pkg) }
    }

    /** [v4] is the IPv4 packet and its parsed UDP header, needed by DnsHandler; null for IPv6. */
    private fun onUdp(
        srcIp: String, dstIp: String, srcPort: Int, dstPort: Int,
        data: ByteArray, v4: Pair<IpPacket, UdpPacket>?,
    ) {
        // DNS over TLS/QUIC and DoH by IP would skip the blocklist. IPv6 never reaches
        // DnsHandler (the tunnel only offers an IPv4 resolver), so plain DNS over
        // IPv6 is dropped as well and the app falls back to it.
        if (DnsBypassGuard.isEncryptedDnsPort(dstPort) ||
            DnsBypassGuard.isDohAddress(dstIp, dstPort) ||
            (v4 == null && DnsBypassGuard.isUnfilteredPlainDns(dstPort))) {
            recordBlock()
            logBlockedAttempt(SessionKey.of(srcIp, srcPort, dstIp, dstPort, IpPacket.PROTO_UDP))
            return
        }
        val key = SessionKey.of(srcIp, srcPort, dstIp, dstPort, IpPacket.PROTO_UDP)
        val existing = sessionTable.get(key)
        if (existing != null) {
            if (blockedOnRecheck(existing)) {
                existing.blocked = true
                sessionTable.remove(key)
                return
            }
            if (dualVpnSettings().dropsUdp(dstPort)) return
            if (dstPort == 443 && interception.blockQuic) return
            udpForwarder.handle(key, data, existing.ownerUid, existing.ownerPackage)
            return
        }

        val uid = resolveOwnerUid(OsConstants.IPPROTO_UDP, key)
        val pkg = appTracker.packageForUid(uid)
        if (v4 != null && dnsHandler.handle(v4.first, v4.second, pkg)) return
        // Dual-hop carries TCP only. QUIC is always dropped so apps retry over TCP
        // through the chain; other UDP only when the user asked for it.
        if (dualVpnSettings().dropsUdp(dstPort)) return
        val decision = filterEngine.evaluate(uid, pkg, null, dstIp, dstPort, IpPacket.PROTO_UDP,
            isBackground = isKnownBackground(pkg))
        if (decision.isBlocked) {
            onBlocked(decision, pkg, dstIp)
            logBlockedAttempt(key, uid, pkg)
            return
        }
        // Drop QUIC (UDP/443) when inspection asks for it: QUIC-capable apps then
        // retry on TCP/TLS, where interception is possible.
        if (dstPort == 443 && interception.blockQuic) return
        udpForwarder.handle(key, data, uid, pkg)
        sessionTable.get(key)?.let { session ->
            if (!session.encryptionClassified) {
                session.encryptionStatus = encEnforcer.inspectUdp(data)
                session.encryptionClassified = true
            }
            onSessionOpened(session, pkg)
        }
    }

    /** Records the app and screen state once, and any hostname DNS already gave this address. */
    private fun onSessionOpened(session: Session, pkg: String?) {
        if (!session.appStateCaptured) captureStartState(session, pkg)
        session.filterCheckedAt = System.currentTimeMillis()
        if (session.resolvedHostname == null) {
            hostByIp[session.key.destinationIp]?.let { session.resolvedHostname = it }
        }
    }

    /**
     * Rules apply to live connections too ("block in background", a new rule), but
     * are only re-evaluated about once a second per connection, not per packet.
     */
    private fun blockedOnRecheck(session: Session): Boolean {
        val now = System.currentTimeMillis()
        if (now - session.filterCheckedAt < FILTER_RECHECK_MS) return false
        session.filterCheckedAt = now
        val pkg = session.ownerPackage
        val decision = filterEngine.evaluate(
            session.ownerUid, pkg, null, session.key.destinationIp, session.key.destinationPort,
            session.key.protocol, isBackground = isKnownBackground(pkg),
        )
        if (decision.isBlocked) onBlocked(decision, pkg, session.key.destinationIp)
        return decision.isBlocked
    }

    /** Collapses the record types and retries of one DNS lookup into one stored row. */
    private val dnsRepeats = com.privacyguard.core.utils.RepeatFilter(DNS_REPEAT_MS)

    /** Five-tuples already logged as blocked: a dropped SYN or UDP packet is retried. */
    private val loggedBlocks = ConcurrentHashMap<SessionKey, Long>()

    /**
     * Records a connection attempt PrivacyGuard dropped before any session existed
     * (a rule, or a DNS-bypass guard), so blocked numbers count connections too, not
     * only DNS lookups. Retries of the same attempt within [BLOCK_DEDUP_MS] are one row.
     */
    private fun logBlockedAttempt(key: SessionKey, knownUid: Int? = null, knownPkg: String? = null) {
        val now = System.currentTimeMillis()
        val last = loggedBlocks.put(key, now)
        if (last != null && now - last < BLOCK_DEDUP_MS) return
        if (loggedBlocks.size > 4_096) loggedBlocks.entries.removeIf { now - it.value > BLOCK_DEDUP_MS }

        val proto = if (key.protocol == IpPacket.PROTO_TCP) OsConstants.IPPROTO_TCP else OsConstants.IPPROTO_UDP
        val uid = knownUid ?: resolveOwnerUid(proto, key)
        val pkg = resolvedPackageName(uid, knownPkg ?: appTracker.packageForUid(uid))
        val dstIp = key.destinationIp
        val isIpv6 = dstIp.contains(':')
        connectionLogger.log(ConnectionEntity(
            appUid = uid,
            appName = resolvedAppLabel(uid, pkg),
            packageName = pkg,
            domain = hostByIp[dstIp],
            destinationIp = dstIp,
            destinationPort = key.destinationPort,
            destinationIpv6 = if (isIpv6) dstIp else null,
            isIPv6 = isIpv6,
            protocol = key.protocolName,
            timestamp = now,
            wasBlocked = true,
            foreground = pkg.takeIf { it.isNotBlank() }?.let { foregroundDetector.stateOf(it) },
            wasBackground = pkg.isNotBlank() && foregroundDetector.stateOf(pkg) == false,
            screenOn = !isPhoneIdle(),
            networkKey = com.privacyguard.app.core.network.NetworkIdentity.current(applicationContext).key,
        ))
    }

    private fun onBlocked(decision: FilterEngine.Decision, pkg: String?, destination: String) {
        recordBlock()
        decision.matchedRule?.id?.let { id -> scope.launch { configDatabase().rulesDao().incrementHitCount(id) } }
        if (decision.matchedRule?.matchBackground == true && !pkg.isNullOrBlank() &&
            backgroundBlockNotifiedPackages.add(pkg)) {
            notifHelper.postBackgroundBlockAlert(pkg, destination, MainActivity::class.java)
        }
    }

    /**
     * Runs once per TCP session on the complete ClientHello (TcpForwarder
     * reassembles it), for IPv4 and IPv6 alike.
     */
    private fun inspectTlsClientHello(data: ByteArray, uid: Int, pkg: String?) {
        // Fast path: use Rust native JA3 computation when available
        val ja3Hash: String? = if (com.privacyguard.core.native_engine.RustBridge.isAvailable) {
            com.privacyguard.core.native_engine.RustBridge.computeJa3(data)
        } else null

        val hello = ClientHelloParser.parse(data) ?: return
        val db = buildDatabase()

        tlsFingerprintMonitor.onClientHello(uid, pkg, hello)

        // JA3 threat check — prefer native hash, fall back to Kotlin-computed hash
        val effectiveHash = ja3Hash ?: hello.ja3Hash()
        val ja3Alert = Ja3Fingerprinter.inspectHash(effectiveHash, hello, pkg)
        if (ja3Alert != null) {
            persistScope.launch {
                db.tlsAlertDao().insert(TlsAlertEntity(
                    timestamp   = ja3Alert.timestamp,
                    alertType   = "JA3_THREAT",
                    hash        = ja3Alert.hash,
                    ja3String   = ja3Alert.ja3String,
                    malwareName = ja3Alert.malwareName,
                    category    = ja3Alert.category,
                    severity    = ja3Alert.severity,
                    sni         = ja3Alert.sni,
                    packageName = ja3Alert.packageName,
                ))
            }
            // No package or SNI: warnings survive R8, and logcat is readable via adb/bug reports.
            Log.w(TAG, "JA3 THREAT: ${ja3Alert.malwareName} hash=${ja3Alert.hash}")
            if (ja3NotifiedHashes.add(ja3Alert.hash)) {
                notifHelper.postJa3ThreatAlert(
                    malwareName = ja3Alert.malwareName,
                    packageName = ja3Alert.packageName,
                    sni = ja3Alert.sni,
                    mainActivityClass = MainActivity::class.java,
                )
            }
        }

        // Cipher suite weakness check
        val cipherReport = CipherSuiteAnalyzer.analyze(hello)
        if (cipherReport.riskLevel == CipherRisk.CRITICAL || cipherReport.riskLevel == CipherRisk.HIGH) {
            persistScope.launch {
                db.tlsAlertDao().insert(TlsAlertEntity(
                    timestamp   = System.currentTimeMillis(),
                    alertType   = "WEAK_CIPHER",
                    severity    = if (cipherReport.riskLevel == CipherRisk.CRITICAL) 9 else 6,
                    sni         = hello.sni,
                    packageName = pkg,
                    category    = "CIPHER_WEAKNESS",
                    detail      = cipherReport.issues.take(3).joinToString("; "),
                ))
            }
        }

        // Register SNI with CT monitor
        hello.sni?.let { ctMonitor.addDomain(it) }  // ignored while CT checks are off
    }

    private fun recordBlock() {
        val n = totalBlocked.incrementAndGet()
        StatsManager.incrementBlocked()
        if (n % 25 == 0L) notifHelper.updateVpnNotification(n, MainActivity::class.java)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sessions and history
    // ─────────────────────────────────────────────────────────────────────────

    private inner class SessionLifecycleListener : SessionTable.Listener {
        override fun onSessionCreated(session: Session) {
            val resolvedPackage = resolvedPackageName(session.ownerUid, session.ownerPackage)
            val appLabel = resolvedAppLabel(session.ownerUid, resolvedPackage)
            StatsManager.update {
                copy(activeConnections = activeConnections + ActiveConnectionInfo(
                    id = session.key.toString(),
                    appName = appLabel,
                    packageName = resolvedPackage,
                    destination = "${session.key.destinationIp}:${session.key.destinationPort}",
                    destinationIp = session.key.destinationIp,
                    destinationPort = session.key.destinationPort,
                    protocol = session.key.protocolName,
                    isBlocked = false,
                    bytesTransferred = 0L,
                    hostName = session.hostname ?: hostByIp[session.key.destinationIp],
                    securityInfo = session.encryptionStatus.name,
                    encryptionInfo = session.tlsVersion?.name ?: "",
                ))
            }
        }

        override fun onSessionClosed(session: Session) {
            val sessionId = session.key.toString()
            StatsManager.update {
                copy(activeConnections = activeConnections.filter { it.id != sessionId })
            }

            com.privacyguard.app.vpn.UidMapper.evict(session.key.sourcePort, session.key.protocol)
            val snapshot = session.snapshot()
            if (snapshot.encryptionStatus == EncryptionStatus.CLEARTEXT &&
                !com.privacyguard.app.core.network.LocalNetwork.isLocalAddress(snapshot.key.destinationIp) &&
                !com.privacyguard.app.core.network.ConnectivityProbes.isProbe(snapshot.ownerPackage, snapshot.hostname)) {
                totalCleartext.incrementAndGet()
                val pkg = snapshot.ownerPackage
                if (!pkg.isNullOrBlank() && cleartextNotifiedPackages.add(pkg)) {
                    val settings = SettingsPreferences.getInstance(applicationContext)
                    if (!blockCleartextRuleActive && settings.shouldShowNotification(
                            com.privacyguard.app.domain.model.NotificationType.CONNECTION_BLOCKED)) {
                        notifHelper.postCleartextAlert(
                            packageName = pkg,
                            destination = snapshot.hostname ?: snapshot.key.destinationIp,
                            mainActivityClass = MainActivity::class.java,
                        )
                    }
                }
            }

            // In-memory work happens now, so a shutdown that closes every session at
            // once still records them all before the final profile save.
            metadataEngine.record(
                snapshot = snapshot,
                isBackground = snapshot.wasBackground,
                encStatus = snapshot.encryptionStatus,
                tlsVer = snapshot.tlsVersion,
                sni = snapshot.tlsSni,
            )

            val resolvedPackage = resolvedPackageName(snapshot.ownerUid, snapshot.ownerPackage)
            val appLabel = resolvedAppLabel(snapshot.ownerUid, resolvedPackage)
            val dstIp = snapshot.key.destinationIp
            val isIpv6 = dstIp.contains(':')
            val hostname = snapshot.hostname ?: hostByIp[dstIp]
            val sessionBytes = snapshot.bytesFromDevice + snapshot.bytesToDevice
            closedSessionBytes.addAndGet(sessionBytes)
            // Sessions that never moved a byte are failed or abandoned connection
            // attempts (e.g. browser pre-connects); they are not history, unless we
            // ended them on purpose: then they are a blocked attempt.
            if (sessionBytes > 0 || snapshot.blocked) connectionLogger.log(ConnectionEntity(
                appUid = snapshot.ownerUid,
                appName = appLabel,
                packageName = resolvedPackage,
                domain = hostname,
                destinationIp = dstIp,
                destinationPort = snapshot.key.destinationPort,
                destinationIpv6 = if (isIpv6) dstIp else null,
                isIPv6 = isIpv6,
                sniHostname = snapshot.tlsSni,
                protocol = snapshot.key.protocolName,
                bytesSent = snapshot.bytesFromDevice,
                bytesReceived = snapshot.bytesToDevice,
                wasBlocked = snapshot.blocked,
                timestamp = snapshot.createdAt,
                durationMs = snapshot.ageMs,
                encryptionStatus = snapshot.encryptionStatus.name,
                tlsVersion = snapshot.tlsVersion?.name,
                wasBackground = snapshot.wasBackground,
                foreground = snapshot.foregroundAtStart,
                screenOn = snapshot.screenOnAtStart,
                networkKey = com.privacyguard.app.core.network.NetworkIdentity.current(applicationContext).key,
            ))

            persistScope.launch {
                // Credit resolver lookups to the app that used the answer (see attributeRecent).
                if (!hostname.isNullOrBlank() && resolvedPackage.isNotBlank() && sessionBytes > 0) {
                    runCatching {
                        buildDatabase().dnsQueryDao().attributeRecent(
                            hostname, resolvedPackage, appLabel,
                            snapshot.createdAt - DNS_ATTRIBUTION_WINDOW_MS, snapshot.createdAt + 2_000L,
                        )
                    }
                }
            }

            StatsManager.update {
                val existing = appStats.find { it.packageName == resolvedPackage }
                val updatedStat = existing?.copy(
                    bytesTransferred = existing.bytesTransferred + sessionBytes,
                ) ?: AppStat(
                    uid = snapshot.ownerUid,
                    packageName = resolvedPackage,
                    appName = appLabel,
                    blockedCount = 0L,
                    bytesTransferred = sessionBytes,
                )
                copy(
                    recentActivity = (listOf(ActivityInfo(
                        appName = appLabel,
                        description = hostname ?: dstIp,
                        timeMillis = snapshot.createdAt,
                        isBlocked = snapshot.blocked,
                    )) + recentActivity).take(50),
                    appStats = (appStats.filter { it.packageName != resolvedPackage } + updatedStat)
                        .sortedByDescending { it.bytesTransferred }
                        .take(50),
                )
            }
        }
    }

    // ── Live sessions ─────────────────────────────────────────────────────────

    /** Bytes of every session closed since the VPN started (for throughput). */
    private val closedSessionBytes = AtomicLong(0)
    private val trafficMeter = com.privacyguard.app.core.stats.LiveTrafficMeter()

    /**
     * Once a second, rebuilds the live list from the session table itself: bytes up
     * and down, rate, the TLS status and host as they are now. Created/closed events
     * alone left every live row at 0 bytes and "unknown" forever.
     */
    private fun startLiveStats() {
        scope.launch {
            var ticks = 0
            while (isRunning) {
                runCatching { publishLiveStats() }
                // The user may switch Always-on / "Block connections without VPN" while we
                // run; only this service can ask, so re-check every 10 s for the Home card.
                if (ticks++ % 10 == 0) {
                    runCatching { com.privacyguard.app.vpn.KillSwitch.updateLockdownState(this@PrivacyVpnService) }
                    probeNetworkOnce()
                }
                kotlinx.coroutines.delay(1_000)
            }
        }
        // Behaviour profiles: every app's, once a minute (and once more on stop), so a
        // killed VPN process loses at most a minute of counts.
        scope.launch {
            var lastSaved = metadataEngine.totalRecorded.get()
            while (isRunning) {
                kotlinx.coroutines.delay(PROFILE_SAVE_MS)
                val recorded = metadataEngine.totalRecorded.get()
                if (recorded != lastSaved) {
                    lastSaved = recorded
                    runCatching { metadataRepo.saveAll(metadataEngine.allProfiles()) }
                    runCatching { notifyBehaviorChanges() }
                }
            }
        }
    }

    /**
     * Large background uploads and other HIGH behaviour findings, as a notification.
     * Runs here, not on a screen, so it works while the app is closed.
     */
    private fun notifyBehaviorChanges() {
        val prefs = getSharedPreferences(com.privacyguard.app.core.behavior.BehaviorNotifier.PREFS, MODE_PRIVATE)
        val notified = prefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()
        val findings = com.privacyguard.app.core.behavior.BehaviorDnaAnalyzer
            .summarizeAll(metadataEngine.allProfiles()).flatMap { it.findings }
        val (due, record) = com.privacyguard.app.core.behavior.BehaviorNotifier.select(findings, notified, System.currentTimeMillis())
        if (due.isEmpty() && record.size == notified.size) return
        prefs.edit().clear().apply { record.forEach { (k, t) -> putLong(k, t) } }.apply()
        due.forEach { f ->
            notifHelper.postBehaviorAlert(
                packageName = f.packageName,
                title = com.privacyguard.app.ui.text.FindingText.title(this, f),
                summary = com.privacyguard.app.ui.text.FindingText.summary(this, f),
                mainActivityClass = MainActivity::class.java,
            )
        }
    }

    /** Networks already checked for DNS hijacking in this VPN session. */
    private val probedNetworks = ConcurrentHashMap.newKeySet<String>()

    /**
     * Once per network: does it answer a name that cannot exist? Only with plain DNS;
     * with DoH on, the network cannot see or change DNS at all.
     */
    private fun probeNetworkOnce() {
        if (!::dnsHandler.isInitialized || dnsHandler.dohEnabled) return
        val key = com.privacyguard.app.core.network.NetworkIdentity.current(applicationContext).key
        if (key == "offline" || !probedNetworks.add(key)) return
        scope.launch {
            if (dnsHandler.probeHijack() == true) {
                reportNetworkSignal(com.privacyguard.app.core.network.NetworkSignals.DNS_HIJACK, "invalid",
                    "A name that cannot exist was answered with an address")
            }
        }
    }

    private fun reportNetworkSignal(type: String, domain: String, detail: String) {
        val severity = when (type) {
            com.privacyguard.app.core.network.NetworkSignals.TLS_INTERCEPTION -> 9
            com.privacyguard.app.core.network.NetworkSignals.DNS_HIJACK -> 8
            else -> 6
        }
        val anomaly = DnsAnomalyDetector.Anomaly(
            timestamp = System.currentTimeMillis(),
            packageName = com.privacyguard.app.core.network.NetworkSignals.NETWORK_OWNER,
            domain = domain,
            type = DnsAnomalyDetector.AnomalyType.valueOf(type),
            description = detail,
            severity = severity,
        )
        // Same path as app anomalies: stored with the network key, deduplicated per day,
        // notified when severe.
        dnsAnomalyDetector.anomalyListener?.onAnomaly(anomaly)
    }

    private fun publishLiveStats() {
        val sessions = sessionTable.allSessions().filter { !it.isClosed }
        val rates = trafficMeter.tick(
            now = System.currentTimeMillis(),
            liveBytes = sessions.associate { it.key.toString() to (it.bytesFromDevice.get() + it.bytesToDevice.get()) },
            closedBytesTotal = closedSessionBytes.get(),
        )
        val live = sessions.map { s ->
            val id = s.key.toString()
            val pkg = resolvedPackageName(s.ownerUid, s.ownerPackage)
            val sent = s.bytesFromDevice.get()
            val received = s.bytesToDevice.get()
            ActiveConnectionInfo(
                id = id,
                appName = resolvedAppLabel(s.ownerUid, pkg),
                packageName = pkg,
                destination = "${s.key.destinationIp}:${s.key.destinationPort}",
                destinationIp = s.key.destinationIp,
                destinationPort = s.key.destinationPort,
                protocol = s.key.protocolName,
                isBlocked = s.blocked,
                bytesTransferred = sent + received,
                hostName = s.hostname ?: hostByIp[s.key.destinationIp],
                securityInfo = s.encryptionStatus.name,
                encryptionInfo = s.tlsVersion?.name ?: "",
                bytesSent = sent,
                bytesReceived = received,
                bytesPerSec = rates.perSession[id] ?: 0L,
            )
        }
        StatsManager.update { copy(activeConnections = live, throughputBytesPerSec = rates.total) }
    }

    private fun resolvedPackageName(uid: Int, packageName: String?): String =
        packageName?.takeIf { it.isNotBlank() }
            ?: appTracker.packageForUid(uid)
            ?: ""

    private val foregroundDetector by lazy { ForegroundAppDetector(this) }

    // ── DNS names by address ──────────────────────────────────────────────────

    /** Last DNS name seen for each answer address, in canonical text form. */
    private val hostByIp = ConcurrentHashMap<String, String>()

    private fun onDnsResolved(ip: String, hostname: String) {
        val address = normalizeIp(ip)
        if (hostByIp.size > 8_192) hostByIp.clear()
        hostByIp[address] = hostname
        sessionTable.allSessions()
            .filter { it.key.destinationIp == address && it.resolvedHostname == null }
            .forEach { it.resolvedHostname = hostname }
        StatsManager.update {
            copy(activeConnections = activeConnections.map { conn ->
                if (conn.destinationIp == address && conn.hostName == null) conn.copy(hostName = hostname) else conn
            })
        }
    }

    /**
     * One spelling per address ("2a03:2880::1" vs the expanded form), matching what
     * Ipv6Packet.parseAddress produces for session keys. Textual only:
     * InetAddress.getByName would start a DNS lookup for anything malformed.
     */
    private fun normalizeIp(ip: String): String {
        if (!ip.contains(':')) return ip
        return runCatching {
            val bytes = ByteArray(16)
            Ipv6Packet.writeAddress(bytes, 0, ip)
            Ipv6Packet.parseAddress(bytes, 0)
        }.getOrDefault(ip)
    }

    /**
     * True only when Usage Access confirms the app is not on screen. Unknown counts as
     * foreground so "block in background" rules never block an app the user is using
     * on a guess.
     */
    private fun isKnownBackground(pkg: String?): Boolean =
        pkg != null && foregroundDetector.stateOf(pkg) == false

    private fun captureStartState(session: Session, pkg: String?) {
        val fg = pkg?.let { foregroundDetector.stateOf(it) }
        session.foregroundAtStart = fg
        session.wasBackground = fg == false
        session.screenOnAtStart = !isPhoneIdle()
        session.appStateCaptured = true
    }

    private fun isPhoneIdle(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isInteractive == false
    }

    private fun resolvedAppLabel(uid: Int, packageName: String?): String {
        val resolvedPackage = resolvedPackageName(uid, packageName)
        return when {
            resolvedPackage.isNotBlank() -> appTracker.labelForPackage(resolvedPackage) ?: resolvedPackage
            uid >= 0 -> appTracker.labelForUid(uid)
            else -> "Unknown"
        }
    }

    /**
     * Owner of a new connection. Android's endpoint-aware API (Q+) comes first; the
     * port-only /proc fallback could match PrivacyGuard's own protected upstream
     * socket and credit other apps' traffic to us. One binder call per connection.
     */
    private fun resolveOwnerUid(protocol: Int, key: SessionKey): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val ownerUid = runCatching {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.getConnectionOwnerUid(
                    protocol,
                    InetSocketAddress(key.sourceIp, key.sourcePort),
                    InetSocketAddress(key.destinationIp, key.destinationPort),
                ) ?: -1
            }.getOrDefault(-1)
            if (ownerUid >= 0) return ownerUid
        }
        return com.privacyguard.app.vpn.UidMapper.uidForSrcPort(key.sourcePort, key.protocol, applicationInfo.uid)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shutdown
    // ─────────────────────────────────────────────────────────────────────────

    private fun stopVpn() {
        stopRequested = true
        startJob?.cancel()
        val wasRunning = isRunning
        isRunning = false
        _isRunningFlow.value = false
        com.privacyguard.app.vpn.BootReceiver.markVpnStopped(this)
        releaseResources()
        if (wasRunning && ::metadataRepo.isInitialized && ::metadataEngine.isInitialized) {
            // After releaseResources: closing the sessions recorded them into the engine.
            val profiles = metadataEngine.allProfiles()
            persistScope.launch { metadataRepo.saveAll(profiles) }
        }
    }

    private fun releaseResources() = synchronized(lifecycleLock) { releaseResourcesLocked() }

    /**
     * Tears down whatever startup managed to build: also used when startup fails
     * half way, so the TUN fd and the forwarder threads never outlive a failed start.
     */
    private fun releaseResourcesLocked() {
        isRunning = false
        running = null
        _isRunningFlow.value = false
        if (receiverRegistered) {
            runCatching { unregisterReceiver(stopReceiver) }
            receiverRegistered = false
        }
        com.privacyguard.app.vpn.KillSwitch.stopMonitoring()
        com.privacyguard.app.vpn.KillSwitch.clearLockdownState()
        // Nothing passes through the tunnel any more; an open capture would only grow stale.
        com.privacyguard.app.core.pcap.PcapWriter.stopCapture()
        if (::ctMonitor.isInitialized) ctMonitor.stop()
        if (::tunReader.isInitialized) tunReader.stop()
        if (::sensorWatcher.isInitialized) sensorWatcher.stop()
        if (::tcpForwarder.isInitialized) tcpForwarder.stop()
        if (::udpForwarder.isInitialized) udpForwarder.stop()
        if (::dnsHandler.isInitialized) dnsHandler.shutdown()
        if (::sessionTable.isInitialized) sessionTable.clear()
        if (::tunWriter.isInitialized) tunWriter.stop()
        com.privacyguard.app.vpn.UidMapper.clear()
        StatsManager.reset()
        runCatching { tunFd?.close() }
        tunFd = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NotificationHelper.ACTION_STOP_VPN) stopVpn()
        }
    }

    private fun buildDatabase(): AppDatabase = AppDatabase.getInstance(this)
    private fun configDatabase(): ConfigDatabase = ConfigDatabase.getInstance(this)
}
