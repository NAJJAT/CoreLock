# PrivacyGuard

A privacy and security VPN app for Android. Runs a local VPN that inspects every network connection on the device — no traffic leaves the device unmonitored, no cloud backend required.

- **No root required**
- **No external servers** — every computation happens on-device
- **Two flavors**: consumer (monitoring + blocking) and enterprise (adds HTTPS payload inspection)

---

## Features

### Core — Consumer build

| Category | What it does |
|---|---|
| **VPN engine** | Local TUN interface intercepts all IPv4 and IPv6 traffic (TCP + UDP). Kill switch blocks all network access if the VPN drops. |
| **Filter rules** | Block by domain, IP/CIDR, app package, or globally. Three protection levels: Minimal / Standard / Strict. Rules take effect instantly — no VPN restart. |
| **Domain blocklist** | Downloads and parses hosts files, EasyList, and domain-list formats. Trie lookup with optional Rust bloom-filter fast path (~5× speedup). |
| **DNS over HTTPS** | RFC 8484 DoH with Cloudflare, Google, and Quad9. Falls back to system DNS automatically. |
| **TLS inspection** | Parses every TLS ClientHello without decrypting payload. Extracts SNI, cipher suites, supported groups, ALPN, and version for every HTTPS connection. |
| **JA3 fingerprinting** | Computes JA3 hash from ClientHello; checks against 35+ known-bad hashes (Cobalt Strike, Emotet, TrickBot, RATs, C2 frameworks). Fires a notification on match. |
| **Cipher suite analysis** | Detects NULL, EXPORT (FREAK/LOGJAM), RC4, DES/3DES (SWEET32), and anonymous ciphers. Risk levels: SAFE → MEDIUM → HIGH → CRITICAL. |
| **Certificate Transparency** | Polls crt.sh every 6 hours for new certificates issued for observed SNI domains. Fires a per-domain notification on new issuance. |
| **DNS anomaly detection** | DGA detection (Shannon entropy + n-gram scoring), DNS tunneling detection (high-entropy labels, TXT record abuse), NXDOMAIN flood detection. |
| **Tracker identification** | Database of 55+ tracker SDKs (company, category, domain patterns). APK scanner does DEX string scan to identify embedded trackers without running the app. |
| **GeoIP / org lookup** | Hardcoded IP range table for major providers (Google, Cloudflare, AWS, Meta, Akamai, etc.). Shown as org hint when SNI is absent (e.g. QUIC traffic). |
| **Background detection** | Tags every session as foreground or background via `ActivityManager`. Supports "block background only" rules per app. |
| **Behavior DNA** | Per-app behavioral fingerprinting: domain diversity, background ratio, cleartext ratio, night-hour activity. Scores and alerts on suspicious patterns. |
| **Privacy grade A–F** | Composite score per app (risk score + cleartext + background + stalkerware). Shown as a colored badge in the Apps screen. |
| **PCAP export** | Writes real PCAP files (LINKTYPE_RAW) for offline analysis in Wireshark or tcpdump. |
| **CSV export** | 7-day connection export with app, domain, IP, bytes, TLS version, background flag, and block status. |
| **IT report** | PDF + JSON compliance report with background/cleartext ratios, TLS version breakdown, and tracker SDK findings. |
| **Weekly report** | WorkManager job generates a weekly summary of blocked connections and top threats. |
| **Quick Settings tile** | VPN toggle directly from the Android pull-down shade. Updates in real time when VPN state changes from any source. |
| **Boot autostart** | Restarts VPN after device reboot if it was running before shutdown. |
| **Dual VPN** | SOCKS5-chained two-hop proxy for additional anonymity. Each hop configured independently in Settings. |
| **MDM / AppConfig** | Full Device Admin + managed configuration support (Intune, Workspace ONE, Jamf). Policy deployed via `RestrictionsManager`. |

### Enterprise build — additional features

| Feature | Description |
|---|---|
| **HTTPS payload inspection (MITM)** | Local CA + per-domain leaf cert forgery. Intercepts and logs HTTP request/response payloads. Consent-gated with a full onboarding wizard. |
| **QUIC block** | Drops UDP/443 to force Chrome, YouTube, and WhatsApp to fall back to TCP TLS, making them interceptable. Toggle in the Payload screen. |
| **PII redactor** | Strips emails, credit cards, phone numbers, JWTs, Bearer tokens, and sensitive JSON fields from logged payloads before storage. |
| **SIEM shipping** | Forwards payload events to a configurable HTTPS endpoint (Splunk, Elastic, etc.) with API-key auth. |
| **Pinning bypass detection** | Skips MITM for apps that use certificate pinning (WhatsApp, Instagram) to avoid breaking their connectivity. |
| **Payload Inspector** | Full-screen chronological list of intercepted payloads. Filter by method, direction, body presence, or flagged status. Risk-scored per entry. |

---

## Screens

| Screen | Description |
|---|---|
| **Home** | VPN toggle, protection level picker (Minimal/Standard/Strict), live throughput tiles, security cards, alert badge |
| **Apps** | All installed apps sorted by risk / data usage / connections / background ratio. Privacy grade A–F badge. Quick block/unblock. |
| **App Detail** | Per-app connection list, domain data usage, hourly activity bar chart, tracker SDK tab, payload tab (enterprise) |
| **Traffic** | Live and 24-hour connection list. App icon with security dot badge (green/amber/red). Filter by app, domain, or security status. |
| **Statistics** | Encryption health gauge, real-time world map, traffic sunburst chart, 7-day × 24h activity heatmap, weekly connection trend, top apps by data |
| **Settings** | All toggles, DoH provider, kill switch, dual VPN config, data retention, diagnostics (external IP, DNS privacy check) |
| **Alert Inbox** | Aggregated DNS anomalies, JA3 threats, weak cipher events, and CT certificate alerts in one chronological list |
| **Security Analysis** | JA3 threat list, cipher alert list, CT events, Rust engine status |
| **Rules** | Full CRUD for domain / IP / CIDR / package rules. Rule hit counters. Suggested rules based on observed behavior. Search bar. |
| **Ads & Trackers** | Top tracker apps by 24h connection volume. Tracker category breakdown. |
| **Onboarding** | First-run consent wizard (3 info pages + required/optional consent checkboxes) |
| **Payload Inspector** | Enterprise only — HTTPS payload capture and search |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        Android App                           │
│                                                              │
│   ┌──────────────┐    ┌──────────────┐    ┌──────────────┐  │
│   │  Compose UI  │◄───│  ViewModels  │◄───│  Room DB     │  │
│   │  (5 tabs)    │    │  + StateFlow │    │  (8 tables)  │  │
│   └──────────────┘    └──────────────┘    └──────────────┘  │
│                              │                               │
│   ┌──────────────────────────────────────────────────────┐  │
│   │                 PrivacyVpnService                     │  │
│   │                                                       │  │
│   │   TUN read → parse → filter → forward → TUN write    │  │
│   │                                                       │  │
│   │   IPv4:  TcpForwarder    UdpForwarder                 │  │
│   │   IPv6:  Ipv6Proxy (TCP relay + self-expiring UDP)    │  │
│   │   DNS:   DnsHandler → DoH (RFC 8484)                  │  │
│   │   TLS:   ClientHelloParser → JA3 → CipherAnalysis     │  │
│   │   MITM:  MitmEngine (enterprise, consent-gated)       │  │
│   └──────────────────────────────────────────────────────┘  │
│                              │                               │
│   ┌──────────────────────────────────────┐                  │
│   │          Rust native engine           │  (optional)      │
│   │  JNI via RustBridge.kt               │                  │
│   │  • processIpv4Packet                 │                  │
│   │  • computeJa3                        │                  │
│   │  • bloomCheck / bloomRebuild         │                  │
│   │  • shannonEntropy                    │                  │
│   └──────────────────────────────────────┘                  │
└─────────────────────────────────────────────────────────────┘
```

### Key data flows

| Flow | Path |
|---|---|
| **Packet** | TUN → `IpPacket.parse()` → `FilterEngine.evaluate()` → `TcpForwarder` / `UdpForwarder` → real socket → TUN |
| **Rule reload** | UI → `RulesRepo` → `RuleSyncBus` (SharedFlow) → `PrivacyVpnService` reloads inline (no restart needed) |
| **Live stats** | `StatsManager` (in-memory) → `StateFlow` → ViewModels → Compose recompose |
| **DB writes** | `ConnectionLogger` → Room (background thread) → `Flow<List<...>>` → ViewModels |
| **Alerts** | VPN detects threat → `NotificationHelper.post*()` → `EXTRA_NAV_ROUTE` deep-link → navigates to Alert Inbox |

---

## Build

### Requirements

- Android Studio Meerkat (or later)
- JDK 17+
- Android SDK — API 36 (compile), API 24 (minimum)

### Debug

```bash
./gradlew assembleConsumerDebug        # consumer flavor — no MITM
./gradlew assembleEnterpriseDebug      # enterprise flavor — MITM enabled
```

### Release

**Step 1 — Generate a keystore** (one-time):

```bash
keytool -genkey -v \
  -keystore privacyguard-release.jks \
  -alias privacyguard \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -dname "CN=PrivacyGuard, OU=Mobile, O=YourOrg, L=City, S=State, C=US"
```

**Step 2 — Get the SHA-256 fingerprint** (used by BuildConfig for cert pinning):

```bash
keytool -list -v \
  -keystore privacyguard-release.jks -alias privacyguard \
  | grep "SHA256:" | awk '{print $2}' | tr -d ':'
```

**Step 3 — Create `release-signing.local.properties`** (gitignored):

```bash
cp release-signing.template.properties release-signing.local.properties
# Fill in all five values
```

**Step 4 — Build**:

```bash
./gradlew assembleConsumerRelease      # signed APK
./gradlew bundleConsumerRelease        # AAB for Play Store
./gradlew assembleEnterpriseRelease    # enterprise APK
```

R8 (code shrinking + obfuscation) runs automatically on all release builds. Rules are in `app/proguard-rules.pro`.

---

## Optional: Rust native engine

The Rust engine provides faster JA3 hashing, bloom-filter domain lookup, and Shannon entropy. Fully optional — Kotlin fallbacks are used automatically when the `.so` is absent.

### Requirements

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
# Install NDK r25+ via Android Studio → SDK Manager → SDK Tools
```

### Build

```bash
cd rust
cargo ndk \
  -t armeabi-v7a -t arm64-v8a -t x86_64 \
  -o ../app/src/main/jniLibs \
  build --release
```

Alternatively, the `buildRust` Gradle task runs `cargo ndk` automatically and silently skips if the toolchain is not installed.

See `rust/BUILD.md` for detailed instructions including cross-compilation notes.

---

## Optional: Flutter UI

A Flutter module in `flutter/` provides an alternative cross-platform UI. The native Kotlin/Compose UI is the default and always available.

```bash
cd flutter && flutter pub get
```

This generates `flutter/.android/` and enables the `:flutter` Gradle project. A **"Flutter UI (Preview)"** option then appears in Settings. The app degrades gracefully — `Class.forName` is used so the APK works whether or not Flutter is compiled in.

---

## Project structure

```
app/src/main/java/
├── com/privacyguard/
│   ├── core/
│   │   ├── filter/       FilterEngine, FilterRule, DomainFilter (trie), IpFilter (CIDR)
│   │   ├── packet/       IpPacket, TcpPacket, UdpPacket, Ipv6Packet
│   │   ├── session/      Session, SessionKey, SessionTable (with reaper)
│   │   ├── tls/          ClientHelloParser, CipherSuiteAnalyzer, Ja3Fingerprinter, CtMonitor
│   │   └── utils/        Checksum (RFC 1071), ByteUtils
│   ├── vpn/
│   │   ├── firewall/     DomainFilter (bloom + trie), IpFilter
│   │   ├── forwarder/    TcpForwarder, UdpForwarder, Ipv6Proxy
│   │   ├── inspector/    EncryptionEnforcer, DnsAnomalyDetector
│   │   ├── mitm/         CaManager, CertForger, MitmEngine, PiiRedactor, PayloadParser
│   │   └── tunnel/       TunInterface, TunReader, TunWriter
│   └── platform/android/ PrivacyVpnService
└── com/privacyguard/app/
    ├── data/db/          Room entities + DAOs (connections, rules, alerts, payloads, …)
    ├── data/local/       SettingsPreferences (all user settings as StateFlow)
    ├── tile/             PrivacyGuardTileService (Quick Settings)
    ├── ui/
    │   ├── dashboard/    Home screen + DashboardViewModel
    │   ├── apps/         AppsScreen, AppDetailScreen
    │   ├── connections/  ConnectionsScreen
    │   ├── statistics/   StatisticsScreen (map, sunburst, heatmap, trend)
    │   ├── alerts/       AlertInboxScreen
    │   ├── mitm/         MitmScreen, PayloadInspectorActivity (enterprise)
    │   ├── onboarding/   OnboardingScreen (3-page consent wizard)
    │   └── settings/     SettingsScreen
    └── vpn/              VpnManager, KillSwitch, UidMapper, BootReceiver

rust/
├── privacyguard-core/    JA3, bloom filter, Shannon entropy, packet classifier (JNI + C FFI)
├── privacyguard-linux/   TUN daemon (tokio async)
├── privacyguard-windows/ WinDivert packet-interception service
└── privacyguard-ebpf/    TC egress/ingress + cgroup_skb programs (aya loader)

flutter/lib/
├── main.dart             5-tab StatefulShellRoute app
├── services/             MethodChannel + EventChannel VPN bridge
└── screens/              Home, Apps, Connections, Stats, Settings, Alerts, AppDetail
```

> **Package naming note**: file paths under `com/privacyguard/app/` sometimes declare `package com.privacyguard.*` (without `.app.`). The Kotlin compiler uses the declared package, not the directory. ProGuard rules cover both naming forms.

---

## Database schema (Room v3)

| Table | Purpose |
|---|---|
| `connections` | Every proxied session — app, domain, IP, ports, bytes, TLS version, background flag, block status |
| `connection_profiles` | Per-app per-domain behavioral summary aggregated over all sessions |
| `rules` | User filter rules with hit counters and optional background-only flag |
| `dns_anomalies` | DGA / tunneling / NXDOMAIN flood events |
| `tls_alerts` | JA3 threat matches, weak cipher detections, and CT new-certificate events |
| `payload_logs` | Intercepted HTTPS payloads (enterprise, MITM-only, PII-redacted) |
| `blocklists` | Downloaded blocklist metadata (URL, last-updated, entry count) |
| `app_stats` | Aggregated per-app traffic counters |
| `network_trust` | Trust level per Wi-Fi SSID |

---

## Permissions

| Permission | Reason |
|---|---|
| `BIND_VPN_SERVICE` | Create the TUN interface |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | VPN runs as a persistent foreground service |
| `QUERY_ALL_PACKAGES` | Resolve app names for all intercepted connections |
| `PACKAGE_USAGE_STATS` | Detect foreground vs. background app state per connection |
| `RECEIVE_BOOT_COMPLETED` | Restart VPN after device reboot |
| `POST_NOTIFICATIONS` | JA3 threat, CT cert, cleartext, and background-block alerts |
| `INTERNET` + `ACCESS_NETWORK_STATE` | DoH queries, CT Monitor polling, SIEM shipping |
| `WRITE_EXTERNAL_STORAGE` (API ≤ 28) | PCAP / CSV export to Downloads |

---

## Build variants

| Variant | Application ID | MITM | Notes |
|---|---|---|---|
| `consumerDebug` | `com.privacyguard.app` | ✗ | Debug certificate, all logging enabled |
| `consumerRelease` | `com.privacyguard.app` | ✗ | R8 + ProGuard, release keystore |
| `enterpriseDebug` | `com.privacyguard.app.enterprise` | ✓ | MITM and payload screens visible |
| `enterpriseRelease` | `com.privacyguard.app.enterprise` | ✓ | R8 + ProGuard, release keystore |

---

## Testing

```bash
./gradlew :app:testConsumerDebugUnitTest
```

147 unit tests across 21 classes — no Android emulator required:

| Area | Tests |
|---|---|
| Packet parsing — `IpPacket`, `TcpPacket`, `UdpPacket` | 36 |
| Checksum (RFC 1071 IP / TCP / UDP) | 14 |
| TLS — `ClientHelloParser`, `Ja3Fingerprinter` | 17+ |
| Cipher suite analysis (NULL / EXPORT / RC4 / DES / ANON) | 16 |
| PII redaction (email, card, JWT, bearer, JSON fields) | 23 |
| Session — `SessionKey`, `SessionTable` (reaper, listeners) | 41 |
| Filter engine (rules, priority, CIDR, background) | — |
| DNS anomaly detection | — |
| Domain filter (trie + bloom) | — |
| Behavior DNA, stalkerware, permission mismatch | — |

---

## License

Private / proprietary. All rights reserved.
