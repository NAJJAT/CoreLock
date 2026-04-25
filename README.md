# PrivacyGuard

**Zero-knowledge network privacy for Android — blocks trackers, encrypts DNS, and gives you full visibility into every connection your apps make.**

No root required. No external servers. Every computation happens on-device.

---

## Screenshots

> Dashboard · Connections · App Detail · Rules · Statistics · Settings

---

## What It Does

PrivacyGuard runs a local VPN on your device and intercepts every network packet before it leaves. It applies four layers of protection in real time:

| Layer | What it does |
|---|---|
| **DNS Shield** | Blocks tracker domains at the DNS level (NXDOMAIN before any connection is made). Supports DNS over HTTPS (Cloudflare / Google / Quad9). Detects DNS tunneling and DGA beacons. |
| **Tracker Blocking** | Matches connections against 300+ known tracker signatures (advertising, analytics, fingerprinting, crash reporting, social). Powered by a local copy of the Exodus Privacy database. |
| **Encryption Enforcement** | Inspects TLS ClientHello handshakes without decrypting payload. Classifies every session as CLEARTEXT / WEAK_TLS / TLS / TLS_1.3. Can block all HTTP (cleartext) connections. |
| **Custom Rules** | Per-domain and per-app block/allow rules. Whitelist an app to bypass VPN entirely (useful for banking apps). |

**Zero data ever leaves your device.** No analytics, no telemetry, no account required.

---

## Features

### Protection
- Local VPN — no root, no kernel module, uses Android `VpnService` API
- Three blocking modes: **Minimal**, **Standard**, **Strict**
- Block by domain, IP/CIDR, app UID, port, or encryption status
- DNS over HTTPS (RFC 8484) — Cloudflare, Google, Quad9
- Kill switch — posts a notification and attempts auto-restart if VPN drops unexpectedly
- Blocklist auto-update from StevenBlack, EasyList, EasyPrivacy, OISD, Hagezi

### Visibility
- **Live connections** — every open TCP/UDP session with app name, destination, protocol, encryption status
- **App Detail** — per-app connection history + APK tracker SDK scan (finds embedded SDKs even if they haven't phoned home yet)
- **Statistics** — blocked count, data saved, top trackers by company, top apps by activity
- **Recent activity feed** — last 50 events on the dashboard
- **GeoIP** — identifies the organisation behind each IP (Google, Cloudflare, AWS, Meta, Akamai, etc.)

### Advanced
- PCAP export — capture raw packets to `.pcap` for Wireshark analysis
- Per-app rules — block a tracker in App A but allow it in App B
- Exodus tracker database — automatically refreshed weekly from `exodus-privacy.eu.org`
- TLS anomaly detection — flags unencrypted background connections, beacon patterns, DGA domains

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│  UI Layer  (Jetpack Compose / Material3)                 │
│  Dashboard · Connections · Apps · Statistics · Rules     │
│  Settings · App Detail                                   │
└────────────────────────┬────────────────────────────────┘
                         │ StateFlow / collectAsState
┌────────────────────────▼────────────────────────────────┐
│  Engine Layer  (Kotlin)                                  │
│                                                          │
│  PrivacyVpnService  ──►  SessionTable                    │
│       │                       │                          │
│       ▼                       ▼                          │
│  PacketPipeline          StatsManager  ──► UI            │
│  ├─ IpFilter (CIDR)                                      │
│  ├─ DomainFilter (trie)                                  │
│  ├─ AppFilter (UID)                                      │
│  ├─ FilterEngine (rules)                                 │
│  ├─ DnsHandler (DoH / plain UDP)                         │
│  ├─ EncryptionEnforcer (TLS ClientHello)                 │
│  ├─ DnsAnomalyDetector (DGA / tunneling)                 │
│  └─ MetadataEngine (behavioural profiles)                │
│                                                          │
│  TrackerDatabase  ◄──  ExodusUpdater (weekly refresh)   │
│  GeoIpResolver (hardcoded major provider ranges)         │
│  ApkScanner (DEX string scan)                            │
│  PcapWriter (libpcap format)                             │
└────────────────────────┬────────────────────────────────┘
                         │ Room
┌────────────────────────▼────────────────────────────────┐
│  Data Layer                                              │
│  AppDatabase (Room/SQLite)                               │
│  ConnectionEntity · RuleEntity · BlocklistEntity         │
│  ConnectionProfileEntity · DnsAnomalyEntity              │
└─────────────────────────────────────────────────────────┘
```

### Key design decisions

- **Single process** — VPN service, packet processing, and UI all in one Android process. No IPC overhead.
- **Zero decryption** — TLS inspection reads only the plaintext ClientHello record-layer bytes. No MITM, no certificate pinning bypass, no key access.
- **Lock-free hot path** — `SessionTable` uses `ConcurrentHashMap`; `FilterEngine` uses `CopyOnWriteArrayList`. The packet thread never blocks on UI updates.
- **Reactive UI** — `StatsManager` is a `StateFlow` singleton. The UI collects it; the VPN engine writes to it. No polling.
- **Trie-based domain matching** — O(L) insert and lookup where L = number of domain labels. Atomic trie swap on blocklist rebuild so the hot path never sees a partial update.

---

## Project Structure

```
app/src/main/java/
├── com/privacyguard/
│   ├── app/
│   │   ├── core/
│   │   │   ├── apk/           ApkScanner — DEX tracker SDK detection
│   │   │   ├── blocklist/     BlocklistManager, BlocklistSource, ExodusUpdater
│   │   │   ├── filter/        FilterEngine, FilterRule
│   │   │   ├── geoip/         GeoIpResolver — offline IP→org lookup
│   │   │   ├── packet/        IpPacket, TcpPacket, UdpPacket, DnsPacket
│   │   │   ├── pcap/          PcapWriter — real libpcap format
│   │   │   ├── session/       SessionTable, Session, SessionKey
│   │   │   ├── stats/         StatsManager — reactive stats singleton
│   │   │   ├── tracker/       TrackerDatabase — 55+ built-in trackers
│   │   │   └── utils/         ByteUtils, Checksum
│   │   │
│   │   ├── data/
│   │   │   ├── db/            Room entities + DAOs
│   │   │   ├── local/         SettingsPreferences (DoH, kill switch, etc.)
│   │   │   ├── remote/        BlocklistDownloader
│   │   │   └── repository/    ConnectionRepo, RulesRepo, BlocklistRepo, …
│   │   │
│   │   ├── platform/android/  PrivacyVpnService, AppTracker, NotificationHelper
│   │   │
│   │   ├── ui/
│   │   │   ├── apps/          AppsScreen, AppDetailScreen + ViewModels
│   │   │   ├── connections/   ConnectionsScreen + ViewModel
│   │   │   ├── dashboard/     DashboardScreen + ViewModel
│   │   │   ├── rules/         RulesScreen + ViewModel
│   │   │   ├── settings/      SettingsScreen
│   │   │   └── statistics/    StatisticsScreen + ViewModel
│   │   │
│   │   ├── vpn/
│   │   │   ├── firewall/      IpFilter, DomainFilter, AppFilter
│   │   │   ├── forwarder/     TcpForwarder, UdpForwarder, DnsHandler
│   │   │   ├── tunnel/        TunInterface, TunReader, TunWriter
│   │   │   ├── KillSwitch.kt
│   │   │   └── UidMapper.kt
│   │   │
│   │   └── workers/           BlocklistUpdateWorker, WeeklyReportWorker
│   │
│   ├── core/metadata/         MetadataEngine, ConnectionProfile, EncryptionStatus
│   └── vpn/inspector/         EncryptionEnforcer, DnsAnomalyDetector
```

---

## Getting Started

### Requirements

| Tool | Version |
|---|---|
| Android Studio | Hedgehog or newer |
| Android SDK | API 36 (compiles to API 24+) |
| Kotlin | 2.x |
| Gradle | 9.4 |

### Build

```bash
# Clone
git clone https://github.com/ammarnajjar00/privacyguard.git
cd privacyguard

# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Install on connected device
./gradlew installDebug
```

### First run

1. Open the app
2. Tap **START VPN** on the dashboard
3. Accept the Android VPN permission dialog
4. The shield turns green — all traffic is now routed through PrivacyGuard

---

## Screens

### Dashboard
Shows the VPN on/off toggle, real-time blocked count, data saved, active apps, privacy score (0–100), and a live recent-activity feed.

### Network (Connections)
Live list of every open TCP/UDP session. Each row shows the app name, destination IP + port, protocol badge (TCP/UDP/DNS), encryption status, and the organisation behind the IP (Google, Cloudflare, AWS, etc.).

### App Detail
Tap any app to see:
- **Connections tab** — every domain/IP this app has contacted, with tracker name, company, and hit count
- **SDKs tab** — embedded tracker SDKs detected by scanning the APK's compiled bytecode (finds trackers even before they phone home)

### Statistics
Blocked count, estimated data saved, and top blocked trackers — today, this week, and all time.

### Rules
Full CRUD for custom firewall rules:
- Block or allow any domain (wildcard-aware)
- Block or allow any app package
- Enable/disable individual rules without deleting them

### Settings
- **Blocking mode** — Minimal / Standard / Strict
- **DNS over HTTPS** — toggle on/off; choose Cloudflare, Google, or Quad9
- **Kill switch** — alert + auto-restart if VPN drops unexpectedly
- **Blocklist update** — manual trigger or automatic weekly update on Wi-Fi
- **PCAP capture** — start/stop packet capture; export `.pcap` to share with Wireshark
- **Language** — English / Arabic

---

## Blocking Modes

| Mode | What gets blocked |
|---|---|
| **Minimal** | Known-malicious and fingerprinting domains only. Safe for banking apps. |
| **Standard** *(default)* | All advertising and analytics trackers. May occasionally break app features. |
| **Strict** | Everything in the blocklist + all tracker categories. Some apps may break. |

---

## DNS over HTTPS

When enabled, all DNS queries are sent as HTTPS POST requests (RFC 8484) to the chosen provider instead of plain UDP. This prevents your ISP from logging which domains you visit.

Providers:
| Provider | URL |
|---|---|
| Cloudflare | `https://cloudflare-dns.com/dns-query` |
| Google | `https://dns.google/dns-query` |
| Quad9 | `https://dns.quad9.net/dns-query` |

If DoH fails (no internet, provider down), PrivacyGuard automatically falls back to plain UDP DNS so connections are never silently broken.

---

## Blocklists

PrivacyGuard downloads and merges blocklists from:

| Source | Format | Entries |
|---|---|---|
| StevenBlack Unified Hosts | Hosts file | ~100k domains |
| EasyList | AdBlock ABP | ~70k domains |
| EasyPrivacy | AdBlock ABP | ~15k tracker domains |
| OISD Basic | Domain list | ~50k domains |
| OISD Full | Domain list | ~250k domains |
| Hagezi Light | Domain list | ~30k domains |

Updates run automatically once a week on Wi-Fi in the background via WorkManager. You can also trigger a manual update from Settings.

---

## Tracker Database

Built-in signatures for 55+ major tracker SDKs, plus live sync from the Exodus Privacy API (300+ trackers). Each tracker has:

- **Name** — e.g. "Google Firebase Analytics"
- **Company** — e.g. "Google LLC"
- **Category** — Advertising / Analytics / Crash Reporting / Fingerprinting / Social / Profiling
- **Domains** — list of network domains to block
- **Class patterns** — Android class name prefixes used by the APK scanner

---

## PCAP Export

Enable PCAP capture in Settings to record raw packets to a `.pcap` file in the app's private storage. Tap **Export PCAP** to share the file with Wireshark, tcpdump, or any compatible analysis tool.

Note: captured TLS traffic is encrypted. To decrypt it you would need the `SSLKEYLOGFILE` (not supported — PrivacyGuard deliberately does not perform MITM).

---

## Privacy & Security

- **No account required** — the app works fully offline after first blocklist download
- **No analytics** — zero telemetry, no crash reporting to external servers
- **No payload decryption** — TLS inspection reads only the plaintext ClientHello header (SNI hostname + TLS version). PrivacyGuard never holds private keys or certificates
- **Local database only** — all connection history, rules, and stats are stored in a Room/SQLite database on your device
- **GPL v3** — the source code is public; anyone can verify it does exactly what it claims

---

## Permissions

| Permission | Why |
|---|---|
| `BIND_VPN_SERVICE` | Required to create a VPN tunnel via Android VpnService API |
| `FOREGROUND_SERVICE` | Keeps the VPN alive when the app is in the background |
| `RECEIVE_BOOT_COMPLETED` | Auto-start VPN on device boot (optional, toggle in Settings) |
| `INTERNET` | Needed to forward allowed traffic to the real internet and to download blocklist updates |
| `POST_NOTIFICATIONS` | Show the persistent VPN notification and kill-switch alerts (Android 13+) |

PrivacyGuard does **not** request: `READ_CONTACTS`, `ACCESS_FINE_LOCATION`, `READ_CALL_LOG`, `CAMERA`, or any other sensitive permission.

---

## Phase 2 — Flutter + Rust (Cross-Platform)

The `privacyguard_flutter/` directory contains the cross-platform rewrite targeting Android, iOS, Windows, macOS, and Linux.

**Stack:** Flutter (Dart) for UI · Rust for the entire engine · `flutter_rust_bridge` for type-safe FFI

| Platform | Adapter |
|---|---|
| Android | Kotlin `VpnService` → Rust JNI |
| iOS / macOS | Swift `NEPacketTunnelProvider` → Rust FFI |
| Windows | Rust `WinDivert` (user-mode, no driver install) |
| Linux | Rust TUN interface (`CAP_NET_ADMIN`) |

See [`privacyguard_flutter/BUILD.md`](privacyguard_flutter/BUILD.md) for build instructions.

---

## Roadmap

- [x] Android VPN pipeline (TCP/UDP/DNS)
- [x] Domain, IP, app, and port filtering
- [x] TLS ClientHello inspection (zero-decrypt)
- [x] DNS anomaly detection (DGA / DNS tunneling)
- [x] DNS over HTTPS (RFC 8484)
- [x] Kill switch
- [x] Blocklist auto-update (6 sources)
- [x] Tracker database (Exodus Privacy integration)
- [x] APK scanner (embedded SDK detection)
- [x] GeoIP (offline provider lookup)
- [x] PCAP export (libpcap format)
- [x] Live connections UI
- [x] App Detail screen (connections + SDK scan)
- [x] Rules screen (full CRUD)
- [ ] IPv6 TCP/UDP session proxying (FR-VPN-16)
- [ ] IPv6 CIDR blocking (FR-VPN-17)
- [ ] Flutter + Rust cross-platform rewrite
- [ ] iOS / macOS (NEPacketTunnelProvider)
- [ ] Windows (WinDivert)
- [ ] Linux (TUN/nfqueue)
- [ ] Google Play Store release

---

## Contributing

Pull requests are welcome. For major changes, open an issue first to discuss what you'd like to change.

```bash
# Run lint
./gradlew lint

# Run unit tests
./gradlew test

# Check for dependency updates
./gradlew dependencyUpdates
```

Code style: Kotlin official style guide. No trailing whitespace. LF line endings (enforced by `.gitattributes`).

---

## License

```
PrivacyGuard — Zero-knowledge network privacy for Android
Copyright (C) 2026  AMMAR NAJJAR

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <https://www.gnu.org/licenses/>.
```

---

## Acknowledgements

- [StevenBlack/hosts](https://github.com/StevenBlack/hosts) — unified ad/tracker hosts blocklist
- [Exodus Privacy](https://exodus-privacy.eu.org) — Android tracker database
- [EasyList](https://easylist.to) — ad/tracker domain lists
- [OISD](https://oisd.nl) — domain blocklist
- [Hagezi DNS Blocklists](https://github.com/hagezi/dns-blocklists)
- [MaxMind GeoLite2](https://www.maxmind.com) — IP geolocation (optional asset)
