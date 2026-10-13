# CoreLock (PrivacyGuard)

A privacy and security app for Android that runs a **local VPN** on the phone and
shows, per app, where your data goes: which servers, which companies, how much,
when (screen on or off, app open or in the background) and why something looks
suspicious. It blocks ads and trackers at DNS level, watches TLS behaviour, and
explains what it finds in plain language in **English, Dutch, French and Arabic**.

- **No root, no account, no backend.** Analysis runs on the device. The only
  outbound requests are listed in [Network requests the app makes](#network-requests-the-app-makes).
- **Two flavors:** *consumer* (monitoring and blocking) and *enterprise* (adds
  consent-gated HTTPS payload inspection, MDM and SIEM shipping).
- **Honest by design:** the app shows destinations, sizes and timing, not the
  content of encrypted traffic, and every number on the Home screen opens the
  exact list it was counted from.

> App name in code and on the device: **PrivacyGuard** (`com.privacyguard.app`).
> Repository name: **CoreLock**.

**How it works, in plain English:** [docs/HOW_IT_WORKS.md](docs/HOW_IT_WORKS.md)
describes what the code actually does: the path of one connection, how every
number on every screen is calculated, each detector and its thresholds, what is
stored and for how long, what leaves the phone, and the known limits. In short:
all traffic runs through a local VPN on the phone; DNS names are checked against
your rules, the lists for your protection level and an ad-SDK policy; every
connection is relayed and recorded with its app, server, size, timing and
encryption; detectors look for odd DNS names, TLS changes, timer-like contacts,
behaviour changes and stalkerware traits; history stays encrypted on the phone.

---

## Contents

1. [What it does](#what-it-does)
2. [Screens](#screens)
3. [Network requests the app makes](#network-requests-the-app-makes)
4. [What the app can and cannot see](#what-the-app-can-and-cannot-see)
5. [Architecture](#architecture)
6. [Data storage and privacy](#data-storage-and-privacy)
7. [Languages and RTL](#languages-and-rtl)
8. [Build](#build)
9. [Build variants](#build-variants)
10. [Permissions](#permissions)
11. [Enterprise: MDM, payload inspection and SIEM](#enterprise-mdm-payload-inspection-and-siem)
12. [Testing](#testing)
13. [Debug tooling](#debug-tooling)
14. [Optional components](#optional-components)
15. [Project structure](#project-structure)
16. [Known limitations](#known-limitations)
17. [Security](#security)
18. [License](#license)

---

## What it does

### Network protection (both flavors)

| Feature | Details |
|---|---|
| **Local VPN engine** | A TUN interface carries all IPv4 and IPv6 traffic (TCP and UDP). Both address families run through the same TCP state machine (`TcpForwarder`, with flow control and retransmission toward the app) and UDP forwarder (oversized replies are fragmented). The tunnel only opens once rules and blocklists are loaded. Every finished flow is written to history with its app, hostname, bytes and timing. |
| **DNS filtering** | Every lookup goes through `DnsHandler`: blocklists (hosts, EasyList and domain-list formats, hash-set lookup), your own rules, and the per-app ad-SDK policy. A user Allow beats ad, tracker and telemetry lists and the ad-SDK policy; malware and phishing hosts stay blocked unless the user accepts a warning. Blocked names get *NXDOMAIN*, so no connection is made. Lookups go only to the resolver you chose (and that provider's second address); if it cannot be reached the app gets *SERVFAIL*. |
| **DNS over HTTPS** | Optional RFC 8484 DoH to Cloudflare, Google or Quad9. Connects to the provider's literal bootstrap address so the DoH host lookup cannot loop through the tunnel. If DoH fails the lookup is answered with *SERVFAIL*; it is never resent as plain DNS. |
| **Encrypted-DNS bypass guard** | Drops DNS-over-TLS/QUIC and DoH to known resolver IPs from other apps, so they fall back to DNS that the filter can see. |
| **Filter rules** | Block by domain, IP/CIDR, app or globally, optionally "only in the background". Three protection levels (Minimal, Standard, Strict). Rules apply instantly without restarting the VPN. Hit counters and suggested rules. |
| **Kill switch** | Uses Android's *Always-on VPN* with *Block connections without VPN* (only Android can block once the tunnel is gone). The card shows "blocking" only when Android reports lockdown; otherwise the VPN drop alert can only notify you (it cannot restart or block). |
| **Cleartext / weak TLS** | Detects plain-HTTP connections and weak TLS versions and cipher suites (NULL, EXPORT, RC4, DES/3DES, anonymous). Optional rules block them. |
| **Network trust** | Scores each network from network-side signs only: open Wi-Fi, captive portal, tampered DNS answers, DNS hijacking, TLS interception. On a low-trust Wi-Fi it suggests Strict mode. |
| **Dual VPN** | Optional two-hop SOCKS5 chain. Carries every TCP connection, and DNS when DoH is on. QUIC is dropped while it is on so apps use TCP; other UDP goes direct unless "Block UDP outside the chain" is on. |
| **Quick Settings tile and boot start** | VPN toggle in the notification shade; restarts after reboot if it was on. |

### Detection

| Feature | Details |
|---|---|
| **TLS fingerprinting (JA4 + JA3)** | Computed once per connection from the full, reassembled ClientHello. No decryption, no MITM. Each app gets its own baseline, learned over its first **7 days**. After an app update (version code), an Android system update, or a WebView/Conscrypt update, new fingerprints are accepted silently for 24 h. An alert fires only when the **same app** shows an **unknown fingerprint** **outside** that window. The alert says what changed, possible reasons (hidden SDK, impersonation, interception tool, harmless library change) and what to do, with a *Trust* action. |
| **JA3 watch-list** | A small list of JA3 hashes published for known tools. JA3 collides across clients, so a match is a hint, not proof, and the UI says so. |
| **DNS anomalies** | Random-looking (DGA-like) names, tunnelling-like labels, bursts of lookups and repeated beacon-like lookups. |
| **Behavior DNA** | Learns each app's normal rhythm (destinations, hours, upload size, background beacons) and flags changes after the learning period. |
| **Permission mismatch** | Flags apps whose traffic doesn't fit their permissions (e.g. location services without location permission, tracker traffic from a utility app). |
| **Stalkerware signals** | No launcher icon, sideloaded, background location, mostly-background traffic, large background uploads. |
| **Certificate Transparency** | Opt-in. Checks crt.sh for newly issued certificates for domains you contact. |
| **Tracker database** | Curated tracker SDKs (company, category, hosts, class patterns), plus the Exodus list. The APK scanner finds embedded SDKs from DEX strings without running the app. |

### "Why am I seeing this ad?"

An honest explanation screen. It does **not** claim to remove ads inside Facebook
or Instagram.

| Part | What it does |
|---|---|
| **Camera & Mic Watch** | A timeline of microphone and camera use with time and screen on/off, plus the search *"Did any app use my mic between [time] and [time]?"*. Android does not say which app holds the mic, so the app shows the app that was on screen ("likely app"), or "unknown". |
| **Location audit** | Lists apps with *Always* vs *While in use* location access, with one tap to each app's permission page. Background location exists only on Android 10+. "Last background use" per app is a system-only API, so the screen links to Android's Privacy dashboard. |
| **Block ad/tracking SDKs in other apps** | Per-app DNS policy for Meta Audience Network, AppsFlyer, Adjust, Branch and others. Login, push and deep-link hosts are never blocked (`graph.facebook.com`, `firebaseinstallations.googleapis.com`…), so "Continue with Facebook" keeps working. Exceptions are listed on screen. |
| **Reduce ad tracking checklist** | Deep links to Android advertising ID, Meta *Your activity off Meta technologies*, Google *My Ad Center*, Google *Timeline* and mic permission review. Done steps are remembered. |
| **Explainer** | A short plain-language explanation of how ad targeting works and what the app can and cannot change. |

### Home: every number opens its list

- **Same list behind every number.** Each number and card on Home is computed
  from the list it opens, for the same time window, anchored at the same moment
  (`HomeEvidence`). No `LIMIT` anywhere. The list count equals the number shown.
- **What opens what:**
  - *Blocked (24 h)*, *Unencrypted (24 h)* and *Encryption health* open their lists.
  - The security cards (behavior changes, random-looking domains, night activity,
    repeating patterns, uploads, background connections, IPv6 sessions) open their lists.
  - The weekly report (connections, blocked, behavior changes, data) opens its lists.
  - The alert bell shows exactly the inbox count ("99+" above 99).
- **While you slept:** "idle" means the screen was off. Apps are ranked by DNS
  lookups made while the screen was off; the definition is on screen. Tapping
  an app opens its traffic for that window, idle-only.
- **Per-app traffic**, in three tabs:
  - *By purpose:* domains grouped as e.g. "Instagram core", "Meta ads",
    "Braze analytics", "CDN", "Other, owner unknown", each with a one-line
    explanation.
  - *Every connection:* time, domain, company, purpose, source (**TLS SNI**,
    **DNS** or **IP only**), bytes sent/received, screen on/off,
    foreground/background, allowed/blocked, and **why it was flagged**.
  - *Raw DNS lookups.*
- **Domain detail:** owner company, purpose and tracker-database entry,
  frequency over 7 days, the apps that used it, first/last seen and bytes. It
  says plainly that we see destinations and sizes, not encrypted content.
- **Empty states say why:** protection off, app not opened (needs Usage
  Access), nothing while the screen was off, traffic not attributable to an
  app, or history doesn't reach back that far.
- **Friendly names:** app labels and icons everywhere, never package names
  ("Removed app" or "Not attributable to an app" when there is no label).

### Reports and export

- **PCAP export:** real PCAP files (LINKTYPE_RAW) for Wireshark/tcpdump, both
  directions. Stops at 100 MB or when protection stops; not encrypted, deleted
  after 24 hours. Starting or sharing a capture requires device authentication.
- **CSV export:** 7 days of connections (app, domain, IP, bytes, TLS, background, blocked).
- **IT report:** PDF + JSON summary for IT staff (kept in English on purpose).
- **Weekly report:** WorkManager summary notification.

---

## Screens

| Screen | Contents |
|---|---|
| **Onboarding** | First-run consent wizard: what the VPN does, required and optional consents (the optional HTTPS-inspection consent exists only in enterprise builds). |
| **Home** | Protection switch, privacy score, encryption health, blocked/unencrypted counts, throughput, active connections, "While you slept", protection level, security cards, weekly report, PCAP, recommendations, recent alerts. All numbers drill down. |
| **Apps** | Installed apps by risk, data, connections or background ratio; grade A–F; quick block. |
| **App detail** | Permission mismatches, domains, tracker SDKs, activity timeline, block / block-in-background, *See every connection*, payload tab (enterprise). |
| **App traffic** | By purpose / every connection / raw DNS, for any window; optional "only while the screen was off". |
| **Domain detail** | Owner, purpose, tracker entry, frequency, apps, first/last seen, bytes. |
| **Evidence lists** | The list behind each Home number. |
| **Traffic** | Live and recent connections with filters and search. |
| **Statistics** | Encryption, score factors, network map, traffic by organisation, 7×24 heatmap, trend, top apps, CSV/IT exports. |
| **While you slept** | Idle activity by app and domain, with today / 7 / 30-day ranges. |
| **Alert inbox** | DNS anomalies, TLS fingerprint changes, JA3 watch-list hits, weak ciphers, CT events, cleartext apps (last 7 days). |
| **Security analysis** | TLS fingerprint profiles and alerts, cipher alerts, CT events, plain-language explainer. |
| **Why am I seeing this ad?** | Camera & Mic Watch, location audit, ad-SDK blocking, checklist, explainer. |
| **Rules** | Create, edit and search rules; hit counters; suggestions. |
| **Ads & trackers** | Tracker traffic by app and category, block rate. |
| **Settings** | Protection level, security toggles, DoH, kill switch, Dual VPN, CT, external-IP check, app integrity, **language picker**, blocklists (real per-list counts), data retention, diagnostics. |
| **Payload inspector** | Enterprise only: captured HTTPS requests/responses, risk scoring, CA install flow. |

---

## Network requests the app makes

| Request | When | What the other side learns |
|---|---|---|
| Upstream DNS (default 1.1.1.1; configurable, IP address only) or DoH (Cloudflare / Google / Quad9) | Every DNS lookup by any app; only to the chosen provider | The names looked up, from your IP |
| Blocklists (raw.githubusercontent.com, easylist.to, small.oisd.nl, urlhaus.abuse.ch) and the Exodus tracker list | Periodic refresh / "Refresh now" | That the app is installed, from your IP |
| crt.sh (Certificate Transparency) | Only if turned on in Settings | Registrable domains of TLS servers you contact |
| api.ipify.org | Only if "External IP check" is on, when diagnostics run | Your public IP |
| SIEM endpoint (enterprise only, set by MDM) | Only when the administrator enables shipping | Payload metadata; HTTPS with optional pinning and signing |

Everything else (history, fingerprints, baselines, sensor events, reports)
stays on the device.

---

## What the app can and cannot see

| Sees | Does not see |
|---|---|
| Which app opened a connection, to which IP and hostname (DNS or TLS SNI) | The content of encrypted (HTTPS) traffic, unless enterprise MITM is enabled and the app trusts the user CA |
| Bytes sent and received, start time and duration | Which ad you were shown, or what an app sent inside TLS |
| Whether the screen was on, and (with Usage Access) whether the app was on screen | Foreground/background without Usage Access (shown as "unknown", never guessed) |
| DNS lookups, blocked or allowed | The app behind a lookup that goes through Android's shared resolver (see below) |
| When the mic or camera is in use | Which app holds the mic or camera (Android hides it; a likely app is shown) |

**DNS attribution.** Apps resolve names through Android's shared resolver, so a
lookup packet belongs to the resolver, not the app. A lookup is credited to the
app that connects to the answer within a minute; blocked or unused lookups stay
"not attributable".

---

## Architecture

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
│   ├─ DNS:  DnsHandler → blocklists / rules / AdSdkPolicy → DoH     │
│   ├─ TLS:  ClientHelloParser → JA4/JA3 → TlsBaselineEngine         │
│   ├─ Sessions: SessionTable (reaper) → ConnectionLogger (batched)  │
│   ├─ SensorUsageWatcher (mic/camera) · ForegroundAppDetector       │
│   └─ MITM: MitmEngine (enterprise only, consent-gated)             │
│                                                                    │
│ Optional Rust core (JNI): JA3, bloom filter, entropy, classifier   │
└───────────────────────────────────────────────────────────────────┘
```

| Flow | Path |
|---|---|
| **Packet** | TUN → `IpPacket` / `Ipv6Packet` → owner UID (`ConnectivityManager.getConnectionOwnerUid`, `/proc` fallback; looked up once per connection) → `FilterEngine` (re-checked about once a second for live connections) → forwarder → protected socket → TUN |
| **Session → history** | `SessionTable` closes or reaps a session → snapshot (app, hostname, SNI, bytes, foreground, screen) → `ConnectionLogger` batches into Room |
| **App state** | Recorded once when a session opens: foreground from `UsageStatsManager` (`ForegroundAppDetector`), screen from `PowerManager.isInteractive` |
| **Rules** | UI → `RulesRepo` → `RuleSyncBus` → service reloads in place |
| **Home numbers** | `DashboardViewModel` (15 s) → `HomeEvidence.select()` on one 24 h query; drill-downs re-run the same selection for the same anchor time |
| **Alerts** | Detector → notification with a deep link (`pg_nav_route`) → inbox / Security analysis |

**Stack:** Kotlin, Jetpack Compose (Material 3), Navigation Compose, Room +
SQLCipher, WorkManager, AppCompat per-app locales, BouncyCastle and OkHttp
(enterprise), optional Rust (JNI) and Flutter.

---

## Data storage and privacy

- **Two databases.** History (`privacyguard.db`, Room v12) is large and
  disposable. Your configuration (rules, list toggles) lives in a separate
  config database, so clearing history never deletes your rules.
- **Encryption at rest.** SQLCipher, with a passphrase wrapped by an Android
  Keystore key, so the database files are unreadable off the device.
  Plaintext databases from older installs are encrypted in place. The key is
  only unwrapped when a database is first used, off the main thread. SIEM
  events waiting to be sent (enterprise) are held in memory only.
- **History tables:**
  - `connections`: app, IP/port, hostname, SNI, bytes, time, encryption, foreground, screen.
  - `dns_queries`: app (when known), domain, blocked, screen off, app foreground.
  - `connection_profiles`, `dns_anomalies`, `tls_alerts`.
  - `tls_app_profiles` and `tls_fingerprints`: the per-app TLS baseline.
  - `sensor_events`: mic/camera.
  - `network_trust`, `app_stats`, `blocklist`, `payload_logs` (enterprise).
- **Retention.** A daily `PruneWorker` deletes history past the retention you
  choose (7/14/30/90 days) and caps the large tables at 500,000 rows. A
  weekly VACUUM compacts the file. Schemas are exported to `app/schemas/` and
  every migration has an instrumented test.
- **Clear all collected data** (Settings) erases every history table, exports,
  PCAPs and the Certificate Transparency record, and what the running VPN holds
  in memory; settings, rules and blocklists stay.
- **Screen privacy.** `FLAG_SECURE` blocks screenshots and recents thumbnails.
- **Sensitive actions** (exports, PCAP, clearing data) require device
  authentication and are blocked on a tampered runtime. `AppSecurityMonitor`
  checks signature, debugger, root packages, emulator and build tags.
- **Backups.** None. `allowBackup="false"` and `fullBackupContent="false"`
  turn backup off on every Android version, and `data_extraction_rules.xml`
  also excludes device-to-device transfer on Android 12+.

---

## Languages and RTL

- English, **Nederlands**, **Français** and **العربية**, with proper plurals
  (French `one/many/other`, Arabic all six forms).
- **Language picker** in Settings. It uses per-app locales
  (`AppCompatDelegate.setApplicationLocales`, `res/xml/locales_config.xml`) and
  `AppLocalesMetadataHolderService` with `autoStoreLocales` for Android < 13.
- **RTL:**
  - `supportsRtl`; start/end instead of left/right.
  - Auto-mirrored directional icons; direction-neutral separators.
  - The heatmap mirrors its columns together with its labels.
- **Locale formats:** dates and times via `DateUtils`, numbers via
  `NumberFormat`, weekday names via `DateFormatSymbols`. Arabic shows
  Arabic-Indic digits.
- **Kept in English on purpose:** the IT report (for IT staff; PDF canvas text
  shaping), SIEM payloads, logs and brand names.
- **Native review needed:** the translations have not yet been reviewed by
  native speakers.

---

## Build

### Requirements

- Android Studio (recent stable) with JDK 17
- Android SDK: compile and target API 36, minimum API 24
- Optional: Rust + `cargo-ndk` (native core), Flutter SDK (preview UI)

### Debug builds

```bash
./gradlew :app:assembleConsumerDebug      # consumer (no MITM)
./gradlew :app:assembleEnterpriseDebug    # enterprise (MITM, MDM, SIEM)
```

On Windows use `gradlew.bat`.

### Release builds

Release builds **refuse to sign with the debug key**. CI checks that an
unsigned release fails.

1. Create a keystore (one time):

   ```bash
   keytool -genkey -v -keystore privacyguard-release.jks -alias privacyguard \
     -keyalg RSA -keysize 4096 -validity 10000 \
     -dname "CN=PrivacyGuard, OU=Mobile, O=YourOrg, L=City, S=State, C=US"
   ```

2. Get the SHA-256 fingerprint (used by the integrity check):

   ```bash
   keytool -list -v -keystore privacyguard-release.jks -alias privacyguard \
     | grep "SHA256:" | awk '{print $2}' | tr -d ':'
   ```

3. Copy `release-signing.template.properties` to
   `release-signing.local.properties` (git-ignored). Fill in
   `PRIVACYGUARD_RELEASE_STORE_FILE`, `…_STORE_PASSWORD`, `…_KEY_ALIAS`,
   `…_KEY_PASSWORD` and `…_SIGNATURE_SHA256`. Gradle properties or environment
   variables with the same names also work.

4. Build:

   ```bash
   ./gradlew :app:assembleConsumerRelease     # APK
   ./gradlew :app:bundleConsumerRelease       # AAB for Play
   ./gradlew :app:assembleEnterpriseRelease   # enterprise APK
   ```

R8 shrinking and obfuscation run on release builds (`app/proguard-rules.pro`).

---

## Build variants

| Variant | Application ID | Version suffix | MITM | Notes |
|---|---|---|---|---|
| `consumerDebug` | `com.privacyguard.app` | — | ✗ | Debuggable, debug hooks |
| `consumerRelease` | `com.privacyguard.app` | — | ✗ | R8, release keystore required |
| `enterpriseDebug` | `com.privacyguard.app.enterprise` | `-enterprise` | ✓ | Payload screen, MDM receiver, debug hooks |
| `enterpriseRelease` | `com.privacyguard.app.enterprise` | `-enterprise` | ✓ | R8, release keystore required |

`BuildConfig.MITM_AVAILABLE` comes from the flavor only. Enterprise code (MITM
engine, CA manager, payload UI, MDM, SIEM, and their libraries) lives in
`src/enterprise` and is not present in consumer builds.

---

## Permissions

| Permission | Why |
|---|---|
| `BIND_VPN_SERVICE` (service) | Create the TUN interface |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | The VPN runs as a foreground service (`specialUse`, required on API 34+) |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_NETWORK_STATE` | Forward traffic, DoH, network trust per Wi-Fi |
| `QUERY_ALL_PACKAGES` | Show app names and icons for every connection; APK tracker scan |
| `PACKAGE_USAGE_STATS` (user-granted Usage Access) | Foreground/background per connection, likely app for mic/camera, "app not opened" empty state |
| `POST_NOTIFICATIONS` | Alerts (TLS fingerprint change, cleartext, background block, VPN down, weekly report) |
| `RECEIVE_BOOT_COMPLETED` | Restart the VPN after reboot |
| `WAKE_LOCK` | Used by WorkManager for background jobs (blocklist refresh, pruning, weekly report) |
| `WRITE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE` (old Android only) | PCAP/CSV export and CA file on API ≤ 28 |
| Enterprise: `BIND_DEVICE_ADMIN` (receiver) | MDM device admin |
| Debug only: `DUMP` (receiver guard) | Debug hooks callable only from adb |

---

## Enterprise: MDM, payload inspection and SIEM

- **HTTPS payload inspection (MITM).** Off by default and gated by explicit
  consent:
  - The app generates a local CA only once inspection is on and consented to;
    on Android 11+ the user installs it in Settings.
  - It forges per-host leaf certificates and logs requests and responses.
  - Apps that pin certificates or ignore user CAs are passed through as
    metadata-only, and the UI says why.
  - *Block QUIC* drops UDP/443 so browsers fall back to TLS over TCP.
  - Turning inspection off deletes the signing key and tells the user how to
    remove the installed CA.
- **PII redaction**: emails, card numbers, phone numbers, JWTs, bearer tokens
  and sensitive JSON fields. Anything sent to the SIEM is always redacted. The
  local payload log is redacted too while `mitm_redact_pii` is on (the
  default); with it off, the inspector stores exactly what left the phone.
- **SIEM shipping** happens automatically once 100 events are queued or a
  minute after the first one, and "Ship now" sends the same queue.
- **Consent from MDM** (`mitm_consent_given`) is valid for as long as the
  policy asserts it; setting it to false withdraws consent and stops inspection.
- **MDM / managed configuration** (`application_restrictions.xml`): see the
  table below.

| Key | Purpose |
|---|---|
| `mitm_enabled`, `mitm_consent_given`, `ca_install_automatic` | Enable inspection and consent; automatic CA install on managed devices |
| `mitm_redact_pii`, `mitm_retention_days` | PII redaction and payload retention |
| `mitm_ship_to_siem`, `mitm_siem_endpoint`, `mitm_siem_api_key` | SIEM forwarding (HTTPS) |
| `mitm_siem_signing_key`, `mitm_siem_pin_sha256` | Payload signing and certificate pinning for the SIEM endpoint |

A Device Admin receiver supports Intune, Workspace ONE, Jamf and similar.

---

## Testing

```bash
./gradlew :app:testConsumerDebugUnitTest :app:testEnterpriseDebugUnitTest   # JVM, no device
./gradlew :app:lintConsumerDebug :app:lintEnterpriseDebug
./gradlew :app:connectedEnterpriseDebugAndroidTest                         # instrumented (device)
```

- **JVM unit tests:** ~310 shared (consumer) and ~370 including enterprise. They cover:
  - the TCP forwarder end to end against a loopback server (lost segment,
    small window, RSTs), UDP reply fragmentation, resolver address validation
  - packet parsing, IPv6 address formatting, checksums
  - ClientHello parsing, JA4 (including the FoxIO reference vector), JA3, cipher analysis
  - TLS baseline engine
  - session table, filter engine, domain filter, DNS anomalies
  - behavior DNA, permission mismatch, stalkerware
  - ad-SDK policy, PII redaction
  - domain classifier, traffic model, Home evidence filters
- **Instrumented tests:** every Room migration (`AppDatabaseMigrationTest`,
  `ConfigSplitMigrationTest`) against the exported schemas.
- **CI** (`.github/workflows/ci.yml`, on push and PR to `main`):
  - unit tests for both flavors
  - lint for both flavors
  - R8 shrinking of both release variants
  - instrumented tests (Room migrations) on an API 34 emulator
  - "unsigned release must fail" check
  - all actions pinned to commit SHAs
  - Rust `cargo test` + clippy
  - gitleaks secret scan

  Dependabot keeps dependencies current.
- **Device testing:** done on a Samsung Galaxy Note20 (Android 13) with
  Facebook, Instagram, a game and a Samsung system app.

---

## Debug tooling

Debug builds only (`src/debug`, never in release):

- **`DebugHooksReceiver`**, protected by `android.permission.DUMP`, so only adb
  can call it:

  ```bash
  # end the 7-day TLS learning period for one app (to test alerts)
  adb shell am broadcast -a com.privacyguard.debug.TLS_END_LEARNING \
      --es package <pkg> -p com.privacyguard.app[.enterprise]
  # allow screenshots (FLAG_SECURE off) for documentation; send after the app started
  adb shell am broadcast -a com.privacyguard.debug.ALLOW_SCREENSHOTS \
      --ez enabled true -p com.privacyguard.app[.enterprise]
  ```

- **Open a screen directly:**

  ```bash
  adb shell am start -n com.privacyguard.app.enterprise/com.privacyguard.app.MainActivity \
      --es pg_nav_route "appTraffic/com.instagram.android"
  ```

  Routes include `dashboard`, `apps`, `connections`, `statistics`, `settings`,
  `alerts`, `sleepReport`, `adPrivacy`, `crypto`, `rules`, `ads`, `payloads`,
  `evidence/<KIND>`, `appTraffic/<package>?from=&to=&idle=1` and
  `domain/<host>`.

- **`testapp/`** is a separate debug-only app (release variant disabled) for
  testing the interception chain:
  - TLS through OkHttp, BouncyCastle and HttpsURLConnection (several fingerprints)
  - "Record mic 5 s"
  - "Resolve ad/login hosts"

  `-PtestappVersionCode=N` simulates an app update. It is never shipped; delete
  it and its `include` in `settings.gradle.kts` if it isn't wanted.

---

## Optional components

### Rust native core (`rust/`)

| Crate | Purpose |
|---|---|
| `privacyguard-core` | JA3, bloom filter, Shannon entropy, packet classifier (JNI + C FFI) |
| `privacyguard-linux` | TUN daemon (tokio) |
| `privacyguard-windows` | WinDivert interception service |
| `privacyguard-ebpf` | TC and cgroup_skb programs (aya loader) |

Kotlin fallbacks are used automatically when the `.so` is absent. To build for Android:

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
cd rust && cargo ndk -t armeabi-v7a -t arm64-v8a -t x86_64 -o ../app/src/main/jniLibs build --release
```

The `buildRust` Gradle task does this automatically and skips silently if
`cargo-ndk` is missing. See `rust/BUILD.md`.

### Flutter preview UI (`flutter/`)

```bash
cd flutter && flutter pub get
```

This wires the `:flutter` module in. A "Flutter UI (Preview)" entry then
appears in Settings. The Compose UI remains the default, and the APK works
with or without Flutter.

### iOS prototype (`ios/`)

A SwiftUI app plus a `PacketTunnelProvider` network extension. It is a
prototype and not part of the Android build.

---

## Project structure

```
app/src/
├── main/java/com/privacyguard/
│   ├── core/          packet (IPv4/IPv6/TCP/UDP/DNS), session, filter, tls (ClientHello, JA3/JA4, ciphers, CT)
│   ├── vpn/           forwarder (TcpForwarder, UdpForwarder, DnsHandler), tunnel (TUN reader/writer),
│   │                  firewall (domain hash sets/bloom, IP/CIDR), inspector (DNS anomalies, encryption),
│   │                  interception (TLS inspection seam)
│   ├── platform/      PrivacyVpnService, AppTracker, ForegroundAppDetector, NotificationHelper
│   ├── ui/            AppNavHost (navigation, bottom bar, routes)
│   └── app/
│       ├── adprivacy/ SensorUsageWatcher, PermissionAudit, AdSdkPolicy, AdPrivacyLinks + screen
│       ├── core/      tracker (TrackerDatabase, DomainClassifier, Exodus), behavior, detection,
│       │              network trust, privacy score, report, pcap, security, stats, apk, geoip
│       ├── data/      Room entities/DAOs (AppDatabase v10, ConfigDatabase), repositories, preferences
│       ├── vpn/       TlsFingerprintMonitor, KillSwitch, VpnManager, BootReceiver, UidMapper
│       ├── workers/   BlocklistUpdateWorker, PruneWorker, WeeklyReportWorker
│       ├── tile/      Quick Settings tile
│       └── ui/        dashboard, apps, traffic (app traffic, domain detail, evidence),
│                      connections, statistics, sleep, alerts, security, rules, ads,
│                      settings (+ LanguagePicker), onboarding, text (FindingText), theme
├── main/res/          values/strings_*.xml + values-nl / values-fr / values-ar, xml/locales_config.xml
├── enterprise/        MITM engine, CA manager, payload UI, MDM receivers, SIEM, strings_mitm
├── consumer/          no-op interception factory
├── debug/             DebugHooksReceiver
├── test/              JVM unit tests
└── androidTest/       Room migration tests
app/schemas/           exported Room schemas
rust/  flutter/  ios/  testapp/  .github/
```

> Two package roots exist: `com.privacyguard.app.…` (app, UI, data) and
> `com.privacyguard.…` (packet engine: core, vpn, platform, mdm). Every file sits
> in the folder of its declared package.

---

## Known limitations

- **TLS fingerprints:**
  - Learning covers only the code paths an app uses in its first 7 days, so a
    feature used later can alert once.
  - New fingerprints are accepted silently after an update; that is also when a
    malicious SDK could arrive.
  - QUIC (UDP/443) is not fingerprinted.
- **Meta app events** share `graph.facebook.com` with login, so they cannot be
  blocked at DNS without breaking "Continue with Facebook".
- **"First-party" is judged from hostnames.** A first-party label can include
  the maker's own analytics on the same servers. IP-only connections show
  "owner unknown".
- **Usage Access is needed** for foreground/background, the likely app behind
  mic/camera use, and the "app not opened" empty state. Without it the app
  shows "unknown".
- **History is written when a connection closes** (or after 5 minutes idle for
  TCP, 30 s for UDP). Very recent, still-open connections appear a little later.
- **Updating the app stops the VPN** unless Android's Always-on VPN is enabled
  for the app. Enable Always-on VPN for continuous protection.
- **Forwarding stalls under heavy load** (rare TLS `BAD_RECORD_MAC`) were traced
  to server→app segments being dropped at a full TUN queue and never resent.
  `TcpForwarder` now reads only what the app's window and the queue can take
  and retransmits lost segments; this still needs confirming on a device.
- **Large DNS answers** need DNS over TCP, which the tunnel does not offer, so
  answers that do not fit in a 4 KB UDP reply fail.
- **The JA3 watch-list is small and unverified;** treat matches as hints.
- **Translations need native review.**
- **The debug build's integrity check** expects a specific debug certificate;
  on another machine it reports "Signature mismatch" and blocks protected
  actions in debug builds. Set the debug SHA-256 in `app/build.gradle.kts` or
  use a release build.

---

## Security

See [SECURITY.md](SECURITY.md). Report vulnerabilities privately via GitHub's
private vulnerability reporting, not in public issues.

## License

Private / proprietary. All rights reserved.
