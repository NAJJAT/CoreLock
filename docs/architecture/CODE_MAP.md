# Code map

Where things are in the code and how the main parts talk to each other. The path of a
packet and every calculation are explained in [HOW_IT_WORKS.md](HOW_IT_WORKS.md); the
decisions behind the design are in the [ADRs](../adr/README.md).

## Components

```
┌───────────────────────────────────────────────────────────────────┐
│ Jetpack Compose UI ── ViewModels (StateFlow) ── Room (SQLCipher)   │
│        │                     │                                     │
│        │          HomeEvidence · DomainClassifier · AlertFeed      │
│        ▼                                                           │
│ PrivacyVpnService (foreground, specialUse)                         │
│   TUN read (poll) → parse → owner UID → filter → forward → TUN     │
│   ├─ TCP:  TcpForwarder, IPv4 + IPv6 (state machine, flow control, │
│   │        retransmission, ClientHello reassembly)                 │
│   ├─ UDP:  UdpForwarder, IPv4 + IPv6 (fragments oversized replies) │
│   │        + QuicGate (holds new QUIC flows until the SNI is read) │
│   ├─ DNS:  DnsHandler → NameBlockPolicy → upstream DNS or DoH      │
│   ├─ TLS:  ClientHelloParser → JA4/JA3 → TlsBaselineEngine         │
│   ├─ Sessions: SessionTable (reaper) → ConnectionLogger (batched)  │
│   ├─ SensorUsageWatcher (mic/camera) · ForegroundAppDetector       │
│   └─ MITM: MitmEngine (src/enterprise, consent-gated)              │
└───────────────────────────────────────────────────────────────────┘
```

| Flow | Path |
|---|---|
| **Packet** | TUN → `IpPacket` / `Ipv6Packet` → owner UID (`ConnectivityManager.getConnectionOwnerUid`, `/proc` fallback; looked up once per connection) → `FilterEngine` (re-checked about once a second for live connections, `NameRecheck`) → forwarder → protected socket → TUN |
| **Name decision** | DNS, CNAME, HTTPS/SVCB target, TCP SNI, QUIC SNI and the HTTP site name all go through `NameBlockPolicy` (one order, ADR 0002) |
| **Session → history** | `SessionTable` closes or reaps a session → snapshot (app, hostname, SNI, bytes, foreground, screen) → `ConnectionLogger` batches into Room; blocked rows are merged per minute (`BlockRowGate`) |
| **App state** | Recorded once when a session opens: foreground from `UsageStatsManager` (`ForegroundAppDetector`), screen from `PowerManager.isInteractive` |
| **Rules** | UI → `RulesRepo` → `RuleSyncBus` → service reloads in place (`FilterEngine.setRules`, atomic) |
| **Home numbers** | `HomeViewModel` and Security systems (`DashboardViewModel`) reload when the database changes (`ui/DbChanges.kt`, at most every few seconds); "blocked today" is `BlockStats.today` everywhere; other drill-downs re-run `HomeEvidence.select()` for the same anchor time |
| **Alerts** | Detector → notification with a deep link (`pg_nav_route`) → inbox / Security analysis |

**Stack:** Kotlin, Jetpack Compose (Material 3), Navigation Compose, Room + SQLCipher,
WorkManager, AppCompat per-app locales, BouncyCastle and OkHttp. Exact versions are in
`gradle/libs.versions.toml`.

## Databases

| Database | Version | Holds |
|---|---|---|
| `privacyguard.db` (`AppDatabase`) | 13 | History: `connections`, `dns_queries`, `connection_profiles`, `dns_anomalies`, `tls_alerts`, `tls_app_profiles`, `tls_fingerprints`, `sensor_events`, `network_trust`, `app_stats`, `blocklist`, `payload_logs` |
| `config.db` (`ConfigDatabase`) | 2 | Your rules and blocklist switches, kept apart so history migrations can never delete them |

Both are encrypted with SQLCipher. Schemas are exported to `app/schemas/`; every
migration has an instrumented test (`AppDatabaseMigrationTest`, `ConfigSplitMigrationTest`).

## Project structure

```
app/src/
├── main/java/com/privacyguard/
│   ├── core/          packet (IPv4/IPv6/TCP/UDP/DNS), session, filter, tls (ClientHello,
│   │                  JA3/JA4, ciphers, CT), http (site name of plain-HTTP requests), metadata
│   ├── vpn/           forwarder (TcpForwarder, UdpForwarder, DnsHandler, NameBlockPolicy),
│   │                  tunnel (TUN reader/writer), firewall (DomainFilter fingerprints, IP/CIDR,
│   │                  QuicGate, DnsBypassGuard), inspector (DNS anomalies, encryption),
│   │                  interception (TLS inspection seam), dualvpn
│   ├── platform/      PrivacyVpnService, AppTracker, ForegroundAppDetector, NotificationHelper
│   ├── ui/            AppNavHost (navigation, bottom bar, routes)
│   └── app/
│       ├── adprivacy/ SensorUsageWatcher, PermissionAudit, AdSdkPolicy, AdPrivacyLinks + screen
│       ├── core/      blocklist, tracker (TrackerDatabase, DomainClassifier, Exodus), behavior,
│       │              detection, network trust, privacy score, report, pcap, security, stats,
│       │              apk, geoip
│       ├── data/      Room entities/DAOs (AppDatabase v13, ConfigDatabase v2), repositories,
│       │              preferences
│       ├── vpn/       VpnManager, TlsFingerprintMonitor, KillSwitch, ProtectionWatchdog,
│       │              BootReceiver, UidMapper
│       ├── workers/   BlocklistUpdateWorker, PruneWorker, WeeklyReportWorker
│       ├── tile/      Quick Settings tile
│       └── ui/        home, activity, apps, settings, rules, onboarding, dashboard (Security
│                      systems), traffic, statistics, alerts, security, ads, theme, components
├── main/res/          values/strings_*.xml + values-nl / values-fr / values-ar,
│                      xml/locales_config.xml
├── main/assets/       geoip_v4.bin, blocklists/hagezi-tif-mini.txt (malware fallback copy)
├── enterprise/        HTTPS inspection engine, CA manager, payload UI, MDM receivers, SIEM
├── debug/             DebugHooksReceiver, ComponentCatalogActivity (debug builds only)
├── test/              JVM unit tests, fuzz targets (fuzz/), security tests (sectest/)
├── testEnterprise/    JVM tests for the enterprise source set
└── androidTest/       Room migrations, live-list test (nightly), workers
app/schemas/           exported Room schemas
tools/                 geoip generator, phone repro scripts (see docs/README.md)
docs/                  documentation (index: docs/README.md)
.github/               CI workflows, issue forms, Dependabot
```

Two package roots exist: `com.privacyguard.app.…` (app, UI, data) and `com.privacyguard.…`
(packet engine: core, vpn, platform, mdm). Every file sits in the folder of its declared
package. The package name keeps the app's first name, PrivacyGuard; see
[Internal names](#internal-names).

## Internal names

CoreLock was first developed as PrivacyGuard. The app's visible name is CoreLock
everywhere: the app label, notifications, screens, exported files and the documentation.
Internal identifiers keep the original name on purpose, so existing installs can update
without losing data and organisations' setups keep working:

| Identifier | Example | Why it keeps the name |
|---|---|---|
| Application ID | `com.privacyguard.app.enterprise` | Android installs an update only over an app with the same ID; a new ID is a different app, and users would lose everything. |
| Database files | `privacyguard.db` | Renaming the file would leave every user's history behind (the config database is already `config.db`). |
| SharedPreferences files | `privacyguard_settings`, `privacyguard_secure_store`, `privacyguard_boot`, `privacyguard_pause`, `privacyguard_watchdog`, `privacyguard_unexpected_stop` | Settings and state are read from these files by name; a new name starts from defaults. CoreLock uses no DataStore files. |
| Keystore alias | `privacyguard_enterprise_ca` | The HTTPS-inspection CA key is found by its alias; a new alias loses the key and breaks inspection until a new CA is installed. |
| WorkManager workers | `com.privacyguard.app.workers.BlocklistUpdateWorker`, `WeeklyReportWorker`, `PruneWorker` | WorkManager stores the worker's class name with every scheduled job; a moved class makes the stored jobs fail. The unique work names (`blocklist_update`, `weekly_report`, …) do not contain the old name. |
| Notification channel IDs | `privacyguard_high`, `privacyguard_normal`, `privacyguard_low`, `privacyguard_report` | Users' choices for each channel (sound, importance, off) belong to its ID; a new ID resets them. The channel names users see ("Protection status", "Privacy alerts", "Weekly report") don't contain the old name. |
| Intent actions | `com.privacyguard.action.STOP_VPN`, `com.privacyguard.action.RESUME_AFTER_PAUSE`, debug `com.privacyguard.debug.*` | Notifications and alarms already set on a phone carry the old action; the debug actions are used by `tools/` and the docs. |
| SIEM signature header | `X-PrivacyGuard-Sig` | Organisations' SIEM receivers may verify it by name. |
| MDM restriction keys | `mitm_enabled`, `mitm_consent_given`, … | Organisations' MDM profiles set these keys. They do not contain the old name; listed so they are never renamed either. |
| The old inspection CA | CN `PrivacyGuard Enterprise CA`, files `PrivacyGuard-CA-*`, folder `Download/PrivacyGuard/` (Android 9 and older) | The CA was renamed to "CoreLock HTTPS Inspection CA" with the 90-day rotation (ADR 0009); the old name is still recognised so users can be told to remove old CAs. The export folder is a FileProvider path. |
| Download User-Agent, start-up cache header | `PrivacyGuard/1.0`, `privacyguard-blocklist-cache 1` | The User-Agent is checked by `BrandNameTest`; the header identifies cache files written by earlier versions. |
| Release signing properties | `PRIVACYGUARD_RELEASE_STORE_FILE` and the other `PRIVACYGUARD_RELEASE_*` | The owner's signing setup reads these names (`release-signing.properties.template`). |

Code-only names (the `com.privacyguard` packages, class names such as
`PrivacyGuardApplication` and `PrivacyGuardTileService`, the Gradle namespace and project
name, the `Theme.Privacyguard` style) also keep the old name for now; renaming them is
proposed for a future major version in
[ADR 0018](../adr/0018-rename-code-only-names.md). `UserVisibleTextTest` fails when the
old name appears in a string resource or a Kotlin literal outside this list.
