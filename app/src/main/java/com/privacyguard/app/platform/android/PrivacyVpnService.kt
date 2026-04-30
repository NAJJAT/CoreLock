package com.privacyguard.platform.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import com.privacyguard.core.filter.FilterEngine
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
import android.util.Log
import java.net.InetSocketAddress
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong
import com.privacyguard.vpn.mitm.CaManager
import com.privacyguard.vpn.mitm.CertForger
import com.privacyguard.vpn.mitm.MitmConfig
import com.privacyguard.vpn.mitm.MitmEngine
import com.privacyguard.vpn.mitm.PayloadParser
import com.privacyguard.vpn.mitm.PayloadShipper
import com.privacyguard.vpn.mitm.PiiRedactor
import com.privacyguard.vpn.mitm.PinningDetector
import com.privacyguard.data.repository.PayloadLogRepositoryImpl
import com.privacyguard.core.tls.CipherRisk
import com.privacyguard.core.tls.CipherSuiteAnalyzer
import com.privacyguard.core.tls.ClientHelloParser
import com.privacyguard.core.tls.CtMonitor
import com.privacyguard.core.tls.Ja3Fingerprinter
import com.privacyguard.app.data.db.TlsAlertEntity

class PrivacyVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var metadataEngine: MetadataEngine
    private lateinit var dnsHandler: DnsHandler
    private lateinit var dnsAnomalyDetector: DnsAnomalyDetector
    private lateinit var filterEngine: FilterEngine
    private lateinit var domainFilter: DomainFilter
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
    private lateinit var ipv6Proxy: com.privacyguard.vpn.forwarder.Ipv6Proxy
    private lateinit var connectionRepo: ConnectionRepo
    private lateinit var connectionLogger: com.privacyguard.app.data.db.ConnectionLogger
    private lateinit var rulesRepo: RulesRepo
    private lateinit var blocklistRepo: BlocklistRepo
    private lateinit var metadataRepo: MetadataRepo
    private lateinit var dnsAnomalyRepo: DnsAnomalyRepo
    private lateinit var mitmConfig: MitmConfig

    private var tunFd: ParcelFileDescriptor? = null
    private val totalBlocked = AtomicLong(0)
    private val totalCleartext = AtomicLong(0)

    private lateinit var ctMonitor: CtMonitor
    private val ja3NotifiedHashes = mutableSetOf<String>()
    private val cleartextNotifiedPackages = mutableSetOf<String>()
    private val backgroundBlockNotifiedPackages = mutableSetOf<String>()
    @Volatile private var blockCleartextRuleActive = false

    companion object {
        private const val TAG = "PrivacyVpnService"
        const val ACTION_STOP = "com.privacyguard.action.STOP_VPN"

        @Volatile var isRunning = false
            private set

        fun startIntent(ctx: Context) = Intent(ctx, PrivacyVpnService::class.java)
        fun stopIntent(ctx: Context) = Intent(ctx, PrivacyVpnService::class.java).also { it.action = ACTION_STOP }

        private fun ipToString(ip: Int): String {
            return "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VPN service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        if (intent?.action == ACTION_STOP) { stopVpn(); return START_NOT_STICKY }
        if (!isRunning) startVpn()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "VPN service destroyed")
        stopVpn()
        scope.cancel()
        super.onDestroy()
    }

    private fun startVpn() {
        Log.i(TAG, "startVpn() — building components")
        try {
            startVpnInternal()
        } catch (e: Throwable) {
            Log.e(TAG, "FATAL: startVpn crashed", e)
            stopSelf()
        }
    }

    private fun startVpnInternal() {
        com.privacyguard.app.vpn.BootReceiver.markVpnActive(this)
        notifHelper = NotificationHelper(this).also { it.createChannels() }
        startForeground(
            NotificationHelper.NOTIFICATION_ID_VPN,
            notifHelper.buildVpnActiveNotification(0, getMainActivityClass()),
        )

        val db = buildDatabase()

        filterEngine = FilterEngine()
        domainFilter = DomainFilter()
        ipFilter = IpFilter()
        appFilter = AppFilter { uid -> packageManager.getNameForUid(uid) }
        appTracker = AppTracker(this, appFilter)

        connectionRepo   = ConnectionRepo(db.connectionDao())
        connectionLogger = com.privacyguard.app.data.db.ConnectionLogger(db.connectionDao(), scope)
        rulesRepo = RulesRepo(db.rulesDao(), filterEngine)
        blocklistRepo = BlocklistRepo(db.blocklistDao())
        metadataRepo = MetadataRepo(db.connectionProfileDao())
        dnsAnomalyRepo = DnsAnomalyRepo(db.dnsAnomalyDao())

        encEnforcer = EncryptionEnforcer()

        dnsAnomalyDetector = DnsAnomalyDetector()
        dnsAnomalyDetector.anomalyListener = DnsAnomalyDetector.AnomalyListener { anomaly ->
            scope.launch {
                dnsAnomalyRepo.save(anomaly)
                if (anomaly.severity >= 8) {
                    notifHelper.postSuspiciousAlert(
                        appName = appTracker.labelForPackage(anomaly.packageName) ?: anomaly.packageName,
                        domain = anomaly.domain,
                        mainActivityClass = getMainActivityClass(),
                    )
                }
            }
        }

        metadataEngine = MetadataEngine()

        scope.launch {
            rulesRepo.loadIntoEngine()
            val allDomains = blocklistRepo.allDomains()
            domainFilter.rebuild(allDomains)
            metadataEngine.knownTrackers = allDomains.toSet()
            appTracker.preloadInstalledApps()
            com.privacyguard.app.core.tracker.ExodusUpdater.initialize(applicationContext)
            com.privacyguard.app.core.tracker.ExodusUpdater.refresh(applicationContext)
        }

        scope.launch {
            RuleSyncBus.version.collect {
                rulesRepo.loadIntoEngine()
                blockCleartextRuleActive = buildDatabase().rulesDao().getAllRules().any {
                    it.enabled &&
                    it.action == com.privacyguard.core.filter.FilterRule.Action.DENY.name &&
                    it.matchEncryption == com.privacyguard.core.metadata.EncryptionStatus.CLEARTEXT.name
                }
            }
        }

        scope.launch {
            BlocklistSyncBus.version.collect {
                val allDomains = blocklistRepo.allDomains()
                domainFilter.rebuild(allDomains)
                metadataEngine.knownTrackers = allDomains.toSet()
            }
        }

        sessionTable = SessionTable()
        sessionTable.startReaper()
        sessionTable.addListener(SessionLifecycleListener())

        Log.i(TAG, "Calling Builder().establish() — requesting TUN fd")
        val fd = Builder()
            .setSession("PrivacyGuard")
            .addAddress("10.0.0.2", 24)
            .addAddress("fd00:1:fd00:1::2", 64)
            .addDnsServer("8.8.8.8")
            .addDnsServer("8.8.4.4")
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .setMtu(1500)
            .establish() ?: run {
            Log.e(TAG, "establish() returned null — VPN permission not granted")
            stopSelf(); return
        }
        Log.i(TAG, "TUN fd established: $fd")

        tunFd = fd
        tunInterface = TunInterface(fd.fileDescriptor)
        tunWriter = TunWriter(tunInterface).also { it.start() }

        val settings = com.privacyguard.app.data.local.preferences.SettingsPreferences.getInstance(this)
        dnsHandler = DnsHandler(
            filterEngine = filterEngine,
            tunWriter = tunWriter,
            anomalyDetector = dnsAnomalyDetector,
            upstreamDns = settings.upstreamDns.value,
            dohEnabled = settings.dohEnabled.value,
            dohProvider = settings.dohProvider.value,
            protectSocket = { sock -> protect(sock) },
            protectTcpSocket = { sock -> protect(sock) },
        ).also { h ->
            h.resolvedListener = DnsHandler.ResolvedListener { ip, hostname ->
                sessionTable.allSessions()
                    .filter { it.key.destinationIp == ip && it.resolvedHostname == null }
                    .forEach { it.resolvedHostname = hostname }
                StatsManager.update {
                    copy(activeConnections = activeConnections.map { conn ->
                        if (conn.destinationIp == ip && conn.hostName == null)
                            conn.copy(hostName = hostname)
                        else conn
                    })
                }
            }
            h.anomalyListener = DnsHandler.AnomalyListener { /* handled in dnsAnomalyDetector */ }
        }

        filterEngine.blockLevel = runCatching {
            FilterEngine.BlockLevel.valueOf(settings.protectionLevel.value)
        }.getOrDefault(FilterEngine.BlockLevel.STANDARD)
        scope.launch { settings.dohEnabled.collect { dnsHandler.dohEnabled = it } }
        scope.launch { settings.dohProvider.collect { dnsHandler.dohProvider = it } }
        scope.launch {
            settings.protectionLevel.collect { level ->
                filterEngine.blockLevel = runCatching {
                    FilterEngine.BlockLevel.valueOf(level)
                }.getOrDefault(FilterEngine.BlockLevel.STANDARD)
            }
        }

        // ==================== TLS ANALYSIS INITIALIZATION ====================
        val ctNotifiedDomains = mutableSetOf<String>()
        ctMonitor = CtMonitor { domain, entries ->
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
                    notifHelper.postCtCertAlert(domain, entries.size, getMainActivityClass())
                }
            }
        }
        ctMonitor.startMonitoring()
        // ==================== MITM INITIALIZATION ====================
        val caManager = CaManager(this)

        // ADDED: initialize CA before TcpForwarder starts so first HTTPS traffic cannot race ahead of certificate generation.
        val caReady = runBlocking(Dispatchers.IO) { caManager.initialize() }
        if (caReady) {
            val cert = caManager.getCaCert()
            Log.i(TAG, "CaManager ready before forwarder start — cert=${cert?.encoded?.size}B subject=${cert?.subjectDN}")
        } else {
            Log.e(TAG, "CaManager.initialize() failed before forwarder start — MITM payload decryption will be unavailable")
        }

        // Initialize CA asynchronously — must complete before the first TLS SYN arrives.
        scope.launch {
            val ok = caManager.initialize()
            if (ok) {
                val cert = caManager.getCaCert()
                Log.i(TAG, "CaManager ready — cert=${cert?.encoded?.size}B " +
                    "subject=${cert?.subjectDN}")
            } else {
                Log.e(TAG, "CaManager.initialize() returned false — " +
                    "MITM will not work until the CA is regenerated")
            }
        }

        val piiRedactor = PiiRedactor()
        val pinningDetector = PinningDetector()
        mitmConfig = MitmConfig(this)
        val payloadParser = PayloadParser(piiRedactor)
        val payloadShipper = PayloadShipper(mitmConfig)
        val certForger = CertForger(caManager)
        val mitmEngine = MitmEngine(certForger, pinningDetector, caManager, payloadParser, ::protect)
        val payloadLogRepository = PayloadLogRepositoryImpl(
            db.payloadLogDao()
        )
        tcpForwarder = TcpForwarder(
            sessionTable, tunWriter, encEnforcer, filterEngine, ::protect,
            mitmEngine, pinningDetector, mitmConfig, payloadParser, payloadShipper, payloadLogRepository,
            dualVpnConfig = { settings.getDualVpnConfig() },
        ).also { it.start() }
        // ==================== END MITM INITIALIZATION ====================

        udpForwarder = UdpForwarder(sessionTable, tunWriter, ::protect).also { it.start() }

        ipv6Proxy = com.privacyguard.vpn.forwarder.Ipv6Proxy(
            tunWriter          = tunWriter,
            protectSocket      = { sock -> protect(sock) },
            protectUdpSocket   = { sock -> protect(sock) },
            scope              = scope,
        )

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

        if (com.privacyguard.app.vpn.KillSwitch.isEnabled()) {
            com.privacyguard.app.vpn.KillSwitch.startMonitoring(this)
        }

        // Periodic housekeeping: prune old tls_alerts once per day while VPN runs
        scope.launch {
            val dayMs = 24L * 60L * 60L * 1000L
            kotlinx.coroutines.delay(dayMs)
            while (isRunning) {
                val retentionDays = com.privacyguard.app.data.local.preferences.SettingsPreferences
                    .getInstance(applicationContext).retentionDays.value
                val cutoff = System.currentTimeMillis() - retentionDays.toLong() * 86_400_000L
                buildDatabase().tlsAlertDao().pruneOld(cutoff)
                Log.d(TAG, "Periodic tls_alerts prune complete (cutoff=$retentionDays days)")
                kotlinx.coroutines.delay(dayMs)
            }
        }

        isRunning = true
        Log.i(TAG, "VPN fully started — all pillars active")
    }

    private fun onIpv6Packet(raw: ByteArray) {
        val ipv6 = com.privacyguard.core.packet.Ipv6Packet.parse(raw)
        if (ipv6 == null) {
            StatsManager.recordIpv6Blocked(raw.size.toLong())
            return
        }

        // Filter: check IP against blocklist rules before proxying
        val dstIp  = ipv6.destinationIp
        val dstPort = when (ipv6.nextHeader) {
            com.privacyguard.core.packet.Ipv6Packet.PROTO_TCP,
            com.privacyguard.core.packet.Ipv6Packet.PROTO_UDP -> {
                val off = com.privacyguard.core.packet.Ipv6Packet.HEADER_LEN
                if (raw.size >= off + 4)
                    ((raw[off + 2].toInt() and 0xFF) shl 8) or (raw[off + 3].toInt() and 0xFF)
                else 0
            }
            else -> 0
        }

        val decision = filterEngine.evaluate(
            uid = -1, pkg = null, domain = null,
            ip = dstIp, port = dstPort, protocol = ipv6.nextHeader,
        )
        if (decision.isBlocked) {
            recordBlock()
            return
        }

        when (ipv6.nextHeader) {
            com.privacyguard.core.packet.Ipv6Packet.PROTO_TCP,
            com.privacyguard.core.packet.Ipv6Packet.PROTO_UDP -> ipv6Proxy.handle(ipv6)
            else -> StatsManager.recordIpv6Blocked(raw.size.toLong())
        }
    }

    private fun ipv6ToString(raw: ByteArray, offset: Int): String {
        if (raw.size < offset + 16) return "::"
        return (0 until 16 step 2)
            .joinToString(":") { i ->
                val value = ((raw[offset + i].toInt() and 0xFF) shl 8) or (raw[offset + i + 1].toInt() and 0xFF)
                value.toString(16)
            }
    }

    private fun onPacket(ip: IpPacket) {
        Log.d(TAG, "📨 onPacket: ${ip.sourceIp} → ${ip.destinationIp}, proto=${ip.protocol}, len=${ip.totalLength}")

        StatsManager.recordPacket(ip.totalLength.toLong())

        if (ipFilter.isBlocked(ip.destinationIp)) {
            Log.d(TAG, "IP blocked: ${ip.destinationIp}")
            recordBlock()
            return
        }

        when (ip.protocol) {
            IpPacket.PROTO_UDP -> {
                val udp = UdpPacket.parse(ip) ?: return
                val uid = resolveOwnerUid(
                    fallbackUid = com.privacyguard.app.vpn.UidMapper.uidForSrcPort(udp.sourcePort, 17, applicationInfo.uid),
                    protocol = OsConstants.IPPROTO_UDP,
                    sourceIp = ip.sourceIp,
                    sourcePort = udp.sourcePort,
                    destinationIp = ip.destinationIp,
                    destinationPort = udp.destinationPort,
                )
                val pkg = appTracker.packageForUid(uid)
                val sessionId = com.privacyguard.core.session.SessionKey.of(
                    ip.sourceIp,
                    udp.sourcePort,
                    ip.destinationIp,
                    udp.destinationPort,
                    IpPacket.PROTO_UDP,
                ).toString()
                updateActiveConnectionIdentity(sessionId, resolvedAppLabel(uid, pkg), pkg)
                if (dnsHandler.handle(ip, udp, pkg)) return
                val isBackground = if (pkg != null) !appTracker.isInForeground(pkg) else false
                val decision = filterEngine.evaluate(uid, pkg, null, ip.destinationIp, udp.destinationPort, 17, isBackground = isBackground)
                if (decision.isBlocked) {
                    recordBlock()
                    decision.matchedRule?.id?.let { id -> scope.launch { buildDatabase().rulesDao().incrementHitCount(id) } }
                    if (decision.matchedRule?.matchBackground == true && !pkg.isNullOrBlank()
                        && backgroundBlockNotifiedPackages.add(pkg)) {
                        notifHelper.postBackgroundBlockAlert(pkg, ip.destinationIp, getMainActivityClass())
                    }
                    return
                }
                // Drop QUIC (UDP/443) when MITM is active and the block-QUIC option is on.
                // Silently dropping forces QUIC-capable apps (Chrome, WhatsApp, YouTube) to
                // retry on TCP/TLS, where MITM interception is possible.
                if (udp.destinationPort == 443 && mitmConfig.isEnabled && mitmConfig.blockQuicWhenMitm) return

                val udpKey = com.privacyguard.core.session.SessionKey.of(
                    ip.sourceIp, udp.sourcePort, ip.destinationIp, udp.destinationPort, IpPacket.PROTO_UDP)
                val isNewUdpSession = sessionTable.get(udpKey) == null
                udpForwarder.handle(ip, udp, uid, pkg)
                if (isNewUdpSession && pkg != null) {
                    sessionTable.get(udpKey)?.wasBackground = !appTracker.isInForeground(pkg)
                }
            }
            IpPacket.PROTO_TCP -> {
                val tcp = TcpPacket.parse(ip) ?: return
                val uid = resolveOwnerUid(
                    fallbackUid = com.privacyguard.app.vpn.UidMapper.uidForSrcPort(tcp.sourcePort, 6, applicationInfo.uid),
                    protocol = OsConstants.IPPROTO_TCP,
                    sourceIp = ip.sourceIp,
                    sourcePort = tcp.sourcePort,
                    destinationIp = ip.destinationIp,
                    destinationPort = tcp.destinationPort,
                )
                val pkg = appTracker.packageForUid(uid)
                val sessionId = com.privacyguard.core.session.SessionKey.of(
                    ip.sourceIp,
                    tcp.sourcePort,
                    ip.destinationIp,
                    tcp.destinationPort,
                    IpPacket.PROTO_TCP,
                ).toString()
                updateActiveConnectionIdentity(sessionId, resolvedAppLabel(uid, pkg), pkg)
                val isSynPacket = tcp.isSyn && !tcp.flagAck
                val isBg = if (pkg != null) !appTracker.isInForeground(pkg) else false
                val decision = filterEngine.evaluate(uid, pkg, null, ip.destinationIp, tcp.destinationPort, 6, isBackground = isBg)
                if (decision.isBlocked) {
                    recordBlock()
                    decision.matchedRule?.id?.let { id -> scope.launch { buildDatabase().rulesDao().incrementHitCount(id) } }
                    if (decision.matchedRule?.matchBackground == true && !pkg.isNullOrBlank()
                        && backgroundBlockNotifiedPackages.add(pkg)) {
                        notifHelper.postBackgroundBlockAlert(pkg, ip.destinationIp, getMainActivityClass())
                    }
                    return
                }
                // TLS fingerprinting on first data packet to port 443
                if (tcp.destinationPort == 443 && tcp.data.isNotEmpty()) {
                    inspectTlsClientHello(tcp.data, pkg)
                }
                tcpForwarder.handle(ip, tcp, uid, pkg)
                // Keep session state aligned with current foreground/background status
                if (pkg != null) {
                    val tcpKey = com.privacyguard.core.session.SessionKey.of(
                        ip.sourceIp, tcp.sourcePort, ip.destinationIp, tcp.destinationPort, IpPacket.PROTO_TCP)
                    sessionTable.get(tcpKey)?.wasBackground = isBg
                }
            }
        }
    }

    private fun inspectTlsClientHello(data: ByteArray, pkg: String?) {
        // Fast path: use Rust native JA3 computation when available
        val ja3Hash: String? = if (com.privacyguard.core.native_engine.RustBridge.isAvailable) {
            com.privacyguard.core.native_engine.RustBridge.computeJa3(data)
        } else null

        val hello = ClientHelloParser.parse(data) ?: return
        val db = buildDatabase()

        // JA3 threat check — prefer native hash, fall back to Kotlin-computed hash
        val effectiveHash = ja3Hash ?: hello.ja3Hash()
        val ja3Alert = Ja3Fingerprinter.inspectHash(effectiveHash, hello, pkg)
        if (ja3Alert != null) {
            scope.launch {
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
            Log.w(TAG, "JA3 THREAT: ${ja3Alert.malwareName} hash=${ja3Alert.hash} pkg=$pkg sni=${ja3Alert.sni}")
            if (ja3NotifiedHashes.add(ja3Alert.hash)) {
                notifHelper.postJa3ThreatAlert(
                    malwareName = ja3Alert.malwareName,
                    packageName = ja3Alert.packageName,
                    sni = ja3Alert.sni,
                    mainActivityClass = getMainActivityClass(),
                )
            }
        }

        // Cipher suite weakness check
        val cipherReport = CipherSuiteAnalyzer.analyze(hello)
        if (cipherReport.riskLevel == CipherRisk.CRITICAL || cipherReport.riskLevel == CipherRisk.HIGH) {
            scope.launch {
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
        hello.sni?.let { ctMonitor.addDomain(it) }
    }

    private fun recordBlock() {
        val n = totalBlocked.incrementAndGet()
        StatsManager.incrementBlocked()
        if (n % 25 == 0L) notifHelper.updateVpnNotification(n, getMainActivityClass())
    }

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
                    hostName = session.hostname,
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
            if (snapshot.encryptionStatus == EncryptionStatus.CLEARTEXT) {
                totalCleartext.incrementAndGet()
                val pkg = snapshot.ownerPackage
                if (!pkg.isNullOrBlank() && cleartextNotifiedPackages.add(pkg)) {
                    val settings = com.privacyguard.app.data.local.preferences.SettingsPreferences
                        .getInstance(applicationContext)
                    val blockCleartextOn = blockCleartextRuleActive
                    if (!blockCleartextOn && settings.shouldShowNotification(
                            com.privacyguard.app.domain.model.NotificationType.CONNECTION_BLOCKED)) {
                        notifHelper.postCleartextAlert(
                            packageName = pkg,
                            destination = snapshot.hostname ?: snapshot.key.destinationIp,
                            mainActivityClass = getMainActivityClass(),
                        )
                    }
                }
            }

            scope.launch {
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
                connectionLogger.log(ConnectionEntity(
                    appUid = snapshot.ownerUid,
                    appName = appLabel,
                    packageName = resolvedPackage,
                    domain = snapshot.hostname,
                    destinationIp = dstIp,
                    destinationPort = snapshot.key.destinationPort,
                    destinationIpv6 = if (isIpv6) dstIp else null,
                    isIPv6 = isIpv6,
                    sniHostname = snapshot.tlsSni,
                    protocol = snapshot.key.protocolName,
                    bytesSent = snapshot.bytesFromDevice,
                    bytesReceived = snapshot.bytesToDevice,
                    wasBlocked = false,
                    timestamp = snapshot.createdAt,
                    durationMs = snapshot.ageMs,
                    encryptionStatus = snapshot.encryptionStatus.name,
                    tlsVersion = snapshot.tlsVersion?.name,
                    wasBackground = snapshot.wasBackground,
                ))

                if (metadataEngine.totalRecorded.get() % 10 == 0L) {
                    val pkg = resolvedPackage.takeIf { it.isNotBlank() } ?: return@launch
                    val profiles = metadataEngine.profilesForApp(pkg)
                    metadataRepo.saveAll(profiles)
                }

                val hostname = snapshot.hostname ?: snapshot.key.destinationIp
                val sessionBytes = snapshot.bytesFromDevice + snapshot.bytesToDevice
                val pkg = resolvedPackage
                StatsManager.update {
                    val existing = appStats.find { it.packageName == pkg }
                    val updatedStat = existing?.copy(
                        bytesTransferred = existing.bytesTransferred + sessionBytes,
                    ) ?: AppStat(
                        uid = snapshot.ownerUid,
                        packageName = pkg,
                        appName = appLabel,
                        blockedCount = 0L,
                        bytesTransferred = sessionBytes,
                    )
                    copy(
                        recentActivity = (listOf(ActivityInfo(
                            appName = appLabel,
                            description = hostname,
                            timeMillis = snapshot.createdAt,
                            isBlocked = false,
                        )) + recentActivity).take(50),
                        appStats = (appStats.filter { it.packageName != pkg } + updatedStat)
                            .sortedByDescending { it.bytesTransferred }
                            .take(50),
                    )
                }
            }
        }
    }

    private fun resolvedPackageName(uid: Int, packageName: String?): String =
        packageName?.takeIf { it.isNotBlank() }
            ?: appTracker.packageForUid(uid)
            ?: ""

    private fun resolvedAppLabel(uid: Int, packageName: String?): String {
        val resolvedPackage = resolvedPackageName(uid, packageName)
        return when {
            resolvedPackage.isNotBlank() -> appTracker.labelForPackage(resolvedPackage) ?: resolvedPackage
            uid >= 0 -> appTracker.labelForUid(uid)
            else -> "Unknown"
        }
    }

    private fun updateActiveConnectionIdentity(sessionId: String, appLabel: String, packageName: String?) {
        if (appLabel == "Unknown" && packageName.isNullOrBlank()) return
        StatsManager.update {
            copy(
                activeConnections = activeConnections.map { info ->
                    if (info.id == sessionId) {
                        info.copy(
                            appName = if (appLabel != "Unknown") appLabel else info.appName,
                            packageName = packageName?.takeIf { it.isNotBlank() } ?: info.packageName,
                        )
                    } else {
                        info
                    }
                }
            )
        }
    }

    private fun resolveOwnerUid(
        fallbackUid: Int,
        protocol: Int,
        sourceIp: String,
        sourcePort: Int,
        destinationIp: String,
        destinationPort: Int,
    ): Int {
        // FIXED: ask Android's endpoint-aware owner API before trusting the port-only /proc fallback.
        // The /proc fallback can accidentally match PrivacyGuard's own protected upstream socket,
        // which made external app traffic appear as com.privacyguard.app.enterprise.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val ownerUid = runCatching {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.getConnectionOwnerUid(
                    protocol,
                    InetSocketAddress(sourceIp, sourcePort),
                    InetSocketAddress(destinationIp, destinationPort),
                ) ?: -1
            }.getOrDefault(-1)
            if (ownerUid >= 0) return ownerUid
        }

        return fallbackUid
    }

    private fun stopVpn() {
        if (!isRunning) return
        isRunning = false
        com.privacyguard.app.vpn.BootReceiver.markVpnStopped(this)
        runCatching { unregisterReceiver(stopReceiver) }

        scope.launch {
            metadataRepo.saveAll(metadataEngine.allProfiles())
            val retentionDays = com.privacyguard.app.data.local.preferences.SettingsPreferences
                .getInstance(applicationContext)
                .retentionDays
                .value
            connectionRepo.pruneOldRecords(retentionDays)
            metadataRepo.pruneOld(retentionDays.toLong() * 86_400_000L)
            dnsAnomalyRepo.pruneOld(retentionDays)
            val tlsCutoff = System.currentTimeMillis() - retentionDays.toLong() * 86_400_000L
            buildDatabase().tlsAlertDao().pruneOld(tlsCutoff)
        }

        com.privacyguard.app.vpn.KillSwitch.stopMonitoring()
        if (::ctMonitor.isInitialized) ctMonitor.stop()
        tunReader.stop()
        tunWriter.stop()
        tcpForwarder.stop()
        udpForwarder.stop()
        sessionTable.clear()
        com.privacyguard.app.vpn.UidMapper.clear()
        StatsManager.reset()
        runCatching { tunFd?.close() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

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

    private fun buildDatabase(): AppDatabase = AppDatabase.getInstance(this)
}


