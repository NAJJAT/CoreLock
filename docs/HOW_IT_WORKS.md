# How PrivacyGuard works

This document describes what the code does today, not what it was meant to do.
Where a feature has a limit or a known gap, it says so. File names are given so
you can check every statement yourself; paths are relative to
`app/src/main/java/com/privacyguard/` unless they start with `app/src/`.

Contents

1. [The big picture](#1-the-big-picture)
2. [The path of one connection](#2-the-path-of-one-connection)
3. [Architecture in detail](#3-architecture-in-detail)
4. [Protection levels and what decides a block](#4-protection-levels-and-what-decides-a-block)
5. [Detection logic](#5-detection-logic)
6. [Every screen: where each number comes from](#6-every-screen-where-each-number-comes-from)
7. [Privacy: what is stored, for how long, and what leaves the phone](#7-privacy-what-is-stored-for-how-long-and-what-leaves-the-phone)
8. [Consumer and enterprise builds](#8-consumer-and-enterprise-builds)
9. [Known limits](#9-known-limits)

---

## 1. The big picture

PrivacyGuard is an Android app that creates a **local VPN**: a virtual network
interface on the phone that every app's traffic is routed into. Nothing is sent
to a VPN server. PrivacyGuard reads each packet, decides whether to let it out,
opens the real connection itself, and records what happened (which app, which
server, how much data, encrypted or not, screen on or off).

```mermaid
flowchart LR
    subgraph Phone
        A[Apps] -->|every packet| TUN[TUN interface<br/>10.0.0.2]
        TUN --> R[TunReader]
        R -->|DNS to 10.0.0.1| D[DnsHandler]
        R -->|TCP| T[TcpForwarder]
        R -->|UDP| U[UdpForwarder]
        D -->|blocked: NXDOMAIN| W[TunWriter]
        T --> W
        U --> W
        W --> TUN
        D --> DB[(Encrypted database<br/>SQLCipher)]
        T --> DB
        U --> DB
        DB --> UI[Screens, alerts,<br/>weekly report]
    end
    D -->|plain DNS or DoH| RES[Chosen DNS resolver]
    T -->|protected socket| NET[Internet]
    U -->|protected socket| NET
    T -.->|dual-hop on| SOCKS[SOCKS5 hop 1 → hop 2]
```

"Protected socket" means `VpnService.protect()` was called on it, so the
connection PrivacyGuard opens leaves through the real network instead of looping
back into the tunnel.

---

## 2. The path of one connection

Say a news app opens `https://ads.example.net/pixel`.

1. **DNS lookup.** The app asks for the address of `ads.example.net`. The tunnel
   gives apps only one DNS server, `10.0.0.1`, which is PrivacyGuard itself
   (`vpn/forwarder/DnsHandler.kt`).
2. **Anomaly check.** The name is checked for odd shapes and odd rhythms
   (section 5.2). Findings are stored, not used to block.
3. **Block decision** (section 4). If the name is blocked, PrivacyGuard answers
   *NXDOMAIN* ("no such name") at once, records a blocked lookup, and the app never
   connects. Otherwise the query goes to the resolver you chose: plain DNS to
   Cloudflare `1.1.1.1` by default, or DNS over HTTPS when you turn DoH on. The
   answer is checked (it must match the question) and handed back to the app.
   PrivacyGuard remembers which address belongs to which name.
4. **Connection.** The app sends a TCP SYN to the returned address. `TunReader`
   reads the packet and `PrivacyVpnService.onTcp` takes over:
   - DNS-over-TLS (port 853), DNS over QUIC, and DoH to the IP addresses of
     well-known DoH servers are dropped, so apps fall back to DNS that PrivacyGuard
     can see (`vpn/firewall/DnsBypassGuard.kt`).
   - The owning app is found: Android 10+ asks
     `ConnectivityManager.getConnectionOwnerUid`; older versions read
     `/proc/net/tcp` (`app/vpn/UidMapper.kt`).
   - Your rules are checked for the app, address and port.
5. **Relay.** `TcpForwarder` completes the handshake with the app itself and opens
   a protected socket to the server (or a SOCKS5 chain when dual-hop is on). It
   then copies bytes both ways, respecting the app's TCP window.
6. **First bytes.** For HTTPS the first bytes are a TLS *ClientHello*. It carries
   the server name (SNI) in clear text, so the hostname is known even if DNS was
   cached. The ClientHello is also fingerprinted (JA3 and JA4), checked for weak
   cipher suites, and compared with the app's learned TLS baseline (section 5.3).
   For plain HTTP the request line marks the connection as *cleartext*.
7. **Close.** When the connection ends, one row is written to the `connections`
   table: app, hostname (SNI, else the DNS name for that address, else the IP),
   bytes each way, duration, encryption, whether the app was on screen and whether
   the screen was on when it started, and the network it used. The app's profile
   for that host is updated (section 5.1). DNS lookups made by the system resolver
   on the app's behalf in the 60 seconds before are credited to the app.

UDP takes the same path through `UdpForwarder`, without a handshake. QUIC
(HTTP/3 over UDP port 443) is forwarded normally, except that it is dropped when
HTTPS inspection asks for it (enterprise) or while dual-hop is on, so apps retry
over TCP.

---

## 3. Architecture in detail

### 3.1 The tunnel and packet engine

| Part | File | What it does |
|---|---|---|
| Service | `platform/android/PrivacyVpnService.kt` | Builds the tunnel (10.0.0.2/24 and fd00:1:fd00:1::2/64, resolver 10.0.0.1, default routes for IPv4 and IPv6), starts everything below, writes history. |
| Reader | `vpn/tunnel/TunReader.kt` | Reads packets from the TUN file descriptor, parses IPv4 and IPv6, hands them on. |
| Writer | `vpn/tunnel/TunWriter.kt` | One queue and one thread that write packets back to apps (DNS answers, TCP data, UDP replies). |
| TCP | `vpn/forwarder/TcpForwarder.kt` | A TCP state machine per connection: ends the app's connection locally and relays it over a real socket, with flow control and retransmission towards the app. |
| UDP | `vpn/forwarder/UdpForwarder.kt` | One protected datagram socket per flow; replies larger than the MTU are fragmented. |
| Sessions | `core/session/` | The table of live connections; a reaper closes idle ones. |

**IPv6.** TCP and UDP over IPv6 go through the same forwarders as IPv4. Other
IPv6 protocols are dropped and counted (Diagnostics shows the count). DNS over
IPv6 to outside resolvers is dropped so apps use the tunnel's IPv4 resolver.

**Start-up order.** Your rules and the blocklists are loaded *before* the tunnel
opens, so no traffic is ever carried unfiltered. The blocklists come from a plain
cache file of the last loaded lists (`app/core/blocklist/BlocklistCache.kt`;
public lists only), which takes a few seconds. They are then reloaded from the
database in the background, in case they changed while protection was off.
Without a cache (the very first start) the tunnel waits for the database, about
half a minute with the full lists.

### 3.2 DNS

| Feature | Behaviour |
|---|---|
| Resolver | Plain DNS to the address you set (default Cloudflare `1.1.1.1`, with its second address as fallback). Never a different company's resolver. If it cannot be reached, the app gets *SERVFAIL*. |
| DoH | Optional, off by default. Cloudflare (`cloudflare-dns.com`), Google (`dns.google`) or Quad9 (`dns.quad9.net`). Connects to a fixed bootstrap address so looking up the DoH server does not loop through the tunnel; TLS still checks the name. A failed DoH lookup answers *SERVFAIL* and is never resent as plain DNS. |
| Answer checks | The answer must parse and match the question's ID and name, or it is dropped. |
| Network checks | On plain DNS, a public name answered with a private or null address is recorded as *tampered*. A lookup of a random `.invalid` name that gets an address means the network *hijacks* DNS. A DoH certificate that does not validate means something is *intercepting* TLS. These feed network trust (section 5.6). |

### 3.3 Storage

| Store | Content | Protection |
|---|---|---|
| `privacyguard.db` (Room) | History: connections, DNS lookups, app profiles, DNS anomalies, TLS alerts and baselines, network trust, sensor events, app stats, payloads (enterprise), blocklists. | SQLCipher; the key is wrapped by an Android Keystore key (`app/core/security/DatabaseEncryption.kt`). |
| `config.db` | Your rules and blocklist switches. Kept apart so history migrations can never delete rules. | SQLCipher, same scheme. |
| Shared preferences | Settings. | App-private, not encrypted. Passwords (SOCKS hops, SIEM key) are stored separately, encrypted with a Keystore key (`SecureSecretStore`). |
| `files/exports/` | PCAP captures, CSV and IT-report exports. | App-private, **not encrypted** (they are made to be opened by other tools). Deleted after 24 hours. |
| `no_backup/blocklist_*.txt.gz` | Cache of the loaded blocklists. | App-private, plain. Public lists only. |
| `ct_baseline` preferences | Highest certificate ID seen per domain, for Certificate Transparency. | Keys are SHA-256 hashes of the domain, not the names. |

### 3.4 Background work

| Worker | When | What |
|---|---|---|
| `BlocklistUpdateWorker` | Every 7 days | Downloads the lists in 3.5 and tells the VPN to reload. |
| `PruneWorker` | Daily | Deletes history older than the retention period (default 30 days), caps the big tables (500,000 connections, 500,000 DNS lookups, 5,000 payloads), deletes exports older than 24 h. |
| `PruneWorker` (vacuum) | Weekly, charging and idle | Compacts the database so freed space is returned. |
| `WeeklyReportWorker` | Weekly, if switched on | Posts the weekly summary notification. |
| `ExodusUpdater` | At most weekly | Refreshes the Exodus Privacy tracker list. |

### 3.5 Blocklists

| Source | Category | URL host |
|---|---|---|
| StevenBlack hosts | ads | raw.githubusercontent.com |
| EasyList (whole-domain rules only) | ads | easylist.to |
| EasyPrivacy (whole-domain rules only) | trackers | easylist.to |
| OISD small | ads | small.oisd.nl |
| HaGeZi Light | ads | raw.githubusercontent.com |
| URLhaus host file | malware | urlhaus.abuse.ch |
| Built-in seed (about 70 hand-picked names) | ads, trackers, malware, telemetry | in the app |

On a test phone in October 2026 this gave about 179,000 ad names, 43,000 tracker
names, 428 malware names and the built-in telemetry names; 278,000 names in total
at Strict. Lists are held in memory as two hash sets (`vpn/firewall/DomainFilter.kt`);
an entry blocks the name and every subdomain.

---

## 4. Protection levels and what decides a block

### 4.1 Levels (`core/filter/ProtectionLevels.kt`)

| Level | Blocks |
|---|---|
| Minimal | Malware and phishing lists only. |
| Standard (default) | Minimal + ads + trackers. |
| Strict | Standard + system telemetry (Microsoft, Apple, Amazon, Mozilla, Dropbox, Google Safe Browsing). Some features can break. |

A source or category you switch off in Ad privacy stays off at every level.

### 4.2 Order of decisions for a DNS name (`vpn/forwarder/DnsBlockDecision.kt`)

1. **Your Block rule** for the name: blocked.
2. **Malware and phishing lists**: blocked, unless you have an Allow rule for the
   name *and* you accepted the warning shown when you created it ("Allow anyway").
3. **Your Allow rule**: resolved. It beats the ad, tracker and telemetry lists and
   the Ad-SDK policy.
4. **Lists for your level**: blocked.
5. **Ad-SDK policy** (Ad privacy → "Block ad and attribution SDKs", on by
   default): blocks ad and attribution SDK servers when the app asking is not
   that SDK's own company's app, and keeps login, push and deep-link hosts open
   even when a list contains them.

Rules match the exact name you type. Write `*.example.com` to cover
`example.com` and every subdomain. Rules for IPv6 addresses are not supported;
the dialog says so.

### 4.3 Connection rules

Rules can also match an app, an IPv4 address or range, a port, "only in the
background", or a connection's encryption ("block cleartext", "block weak TLS").
They are re-checked every second on live connections, so a new rule cuts an open
connection.

### 4.4 Dual-hop (SOCKS5 chain)

When both hops are set, **every TCP connection** goes device → hop 1 → hop 2 →
server, and **DNS goes through the chain too when DoH is on** (DoH is TCP). SOCKS5
`CONNECT` carries only TCP, so:

- QUIC (UDP 443) is always dropped while dual-hop is on, and apps fall back to TCP
  through the chain.
- Other UDP (calls, games, video chat) goes directly, unless you switch on
  "Block UDP outside the chain", which drops it.
- With DoH off, plain DNS goes directly to your resolver. Turn on DoH.

---

## 5. Detection logic

Nothing in this section blocks traffic. It produces findings, alerts and scores.

### 5.1 Per-destination profiles and risk (`core/metadata/MetadataEngine.kt`)

For each (app, host) pair PrivacyGuard keeps: connection count, bytes each way,
first and last seen, average and shortest interval between connections, share
made in the background, and a 24-hour histogram. The risk score is the sum of
these signals × 10, capped at 100:

| Signal | Points |
|---|---|
| Host or a parent is on any blocklist or the curated ad list ("known tracker") | 6 |
| Unencrypted, and not a local device or an OS connectivity check | 7 |
| Weak TLS version | 4 |
| More than 80 % in the background, over 5 connections | 5 |
| Average interval under 60 s over more than 10 connections, not a push host | 6 |

70 or more is "high risk", 40–69 "medium".

**Tracker names on screens** come from a separate table: 55 hand-written tracker
entries (Google AdMob, Meta, AppsFlyer…) plus the Exodus Privacy list
(`app/core/tracker/`). A tracker owned by the same company as the app is labelled
first-party, not tracker.

### 5.2 DNS anomalies (`vpn/inspector/DnsAnomalyDetector.kt`)

Checked on every lookup an app makes (not PrivacyGuard's own). A, AAAA and HTTPS
queries for one name within 1 second count as one lookup.

| Finding | Rule | Severity |
|---|---|---|
| Many subdomains | More than 5 labels, not a known CDN | 6 |
| Long name | More than 50 characters, not a known CDN | 5 |
| Random-looking name | First label ≥ 12 characters, Shannon entropy ≥ 3.4 bits per character, ≥ 15 % digits, not a CDN. Catches generated (DGA) names while normal words and brand names stay below. | 8 |
| Lookup burst | More than 30 *different* names in 10 seconds | 4 |
| Repeated lookups | The same name 8 or more times in 10 seconds (possible beaconing) | 5 |

Each finding is reported once per app and host per 24 hours. For the three
name-shape findings the base domain is used instead of the host, because CDN-like
hosts are unique by design. CDN suffixes (CloudFront, Akamai, Fastly, Facebook and
TikTok CDNs, Google video…) are exempt from the shape checks.

### 5.3 TLS checks (`core/tls/`)

| Check | Rule | Result |
|---|---|---|
| JA3 threat list | The ClientHello's JA3 hash is one of 35 hashes known from malware families. | Alert, severity from the list, notification once per hash. |
| Weak ciphers offered | The ClientHello *offers* NULL, EXPORT or anonymous suites (critical, severity 9), or more than two of RC4 / DES / 3DES (severity 6). It checks what is offered, not what the server picked. | Alert. |
| TLS version | TLS below 1.2 on the connection marks it *weak TLS*. | Encryption status, risk signal. |
| Fingerprint baseline (`TlsBaselineEngine.kt`) | Per app, the JA4 fingerprints it uses. For the first 7 days every new one is learned. Within 24 hours after the app, the OS build, WebView or Conscrypt is updated, new ones are learned silently. Otherwise a new fingerprint is stored and raises **one** alert. | "TLS fingerprint changed" alert and notification. |
| Certificate Transparency (opt-in) | Every 6 hours, the base domain of up to 50 contacted servers is looked up on crt.sh. The first answer is the baseline; later certificates with a higher ID raise an alert. Turning it off stops at once and forgets the queue. | "New certificate" alert. |

### 5.4 Behaviour changes ("App Behavior DNA", `app/core/behavior/BehaviorDnaAnalyzer.kt`)

An app's baseline is ready after 7 days of history or 40 connections. Then:

| Finding | Rule |
|---|---|
| New destination | A host first seen in the last 24 h with 3+ connections, while the app has older destinations (3+ days). Up to 4 per app. |
| Unusual hour | A host whose busiest hour holds 8 % or less of the app's activity, and is mostly (≥ 50 %) background. |
| Upload spike | 2 MB or more sent to one host in the last 24 h, mostly in the background, and 3× the app's average per host. |
| Background beacon | ≥ 85 % background, 8+ connections, average interval 10 s – 15 min, not a push host. |

All are *medium*: reasons to look, not proof.

### 5.5 Timer-like contacts (Home card, `HomeEvidence.beacons`)

In the last 24 hours, the same app and host contacted at least 6 times, with gaps
between 5 seconds and 10 minutes whose spread is under 25 % of the average gap.
Push channels (Google FCM, Apple, Samsung, Meta and WhatsApp chat, Signal, Mozilla, Windows) and the OS connectivity checks are left out,
because they run on a timer by design.

### 5.6 Network trust (`app/core/network/NetworkTrustAnalyzer.kt`)

Judges the network, not the apps on it. Starts at 100:

| Sign | Penalty |
|---|---|
| TLS interception seen (a well-known server's certificate was not trusted) | −40 |
| DNS hijacking (a reserved `.invalid` name got an address) | −30 |
| Open Wi-Fi without encryption | −30 |
| Tampered DNS answers (public name → private address) | −10 each, at most −30 |
| Captive portal | −15 |

80 or more is *Trusted*, 55–79 *Watch*, below that *Reduced*. On a *Reduced*
Wi-Fi network, Home suggests Strict. Mobile networks are scored but never get the
suggestion. Without location permission Android does not tell apps a Wi-Fi
network's name, so "No thanks" on the suggestion applies to all Wi-Fi networks,
and the card says so.

### 5.7 Stalkerware score (`app/core/detection/StalkerwareDetector.kt`)

For apps that are not preinstalled system apps:

| Signal | Points |
|---|---|
| No launcher icon | 30 |
| Not installed from an app store (Play, Galaxy Store, Huawei, Amazon, Xiaomi, Oppo) | 20 |
| Asks for background location | 20 (foreground location only: 5) |
| A host with ≥ 80 % background traffic | 10 |
| A host with ≥ 10 connections of which half or more were between 00:00 and 05:59 | 15 |
| 1 MB or more uploaded to one host, ≥ 75 % in the background | 15 |

70 or more shows "Possible stalkerware" on the app; 40 or more shows the score.
These are the traits that set monitoring apps apart; traits that ordinary apps
share (any location permission, mostly background traffic on its own) were
removed because they flagged messengers and banking apps.

### 5.8 Camera & Mic Watch (`app/adprivacy/SensorUsageWatcher.kt`)

Android reports *that* the microphone (`AudioRecordingCallback`) or a camera
(`CameraManager.AvailabilityCallback`) is in use, but not *which app*. PrivacyGuard
records start and end and whether the screen was on. If the screen was on, it
names the app that was on screen 1.5 seconds after the start (needs Usage access).
If the screen was off, it names no app ("App not known (screen was off)"),
because the last app on screen is then not the one using the sensor.

### 5.9 Is there a "retro-hunt"?

No. Detection runs on traffic as it happens. When the blocklists or the JA3 list
change, past history is **not** re-scanned and no alerts are raised for old
connections. The only thing that changes for old rows is the *tracker label*
shown on screens, because tracker names are looked up when a screen is drawn,
with the current lists.

---

## 6. Every screen: where each number comes from

All counts come from rows in the database. Each tappable number opens the list it
was counted from, built for the same time window, so the number and the length
of the list are equal (`app/ui/traffic/HomeEvidence.kt`).

**"Blocked"** means the same thing everywhere (`app/data/repository/BlockStats.kt`):
DNS lookups answered *NXDOMAIN* by the lists or your rules, plus connection
attempts PrivacyGuard dropped (rules, DNS-bypass guard, cleartext rule).
**"Block rate"** = blocked lookups ÷ all lookups (lookups, not connections, so a
visit is not counted twice).

**Encryption** counts only connections whose encryption could be seen (a TLS or
QUIC handshake, or a plain HTTP request), left blocked ones, local devices and the
OS's connectivity checks out. Encrypted share = encrypted ÷ (encrypted + cleartext).

### Home

| Number | Calculation |
|---|---|
| Privacy score (0–100) | `app/core/privacy/PrivacyScoreCalculator.kt`, last 24 h: **Encryption** 50 × encrypted share; **High-risk apps** 25 × (1 − apps with a profile ≥ 70 ÷ 10); **Blocklist coverage** 15 × (1.0 for ≥ 250k names at your level, 0.8 for ≥ 100k, 0.55 for ≥ 50k, 0.3 for fewer, 0 for none); **Background** 10 × (1 − background share). Blocked lookups are not an input, so blocking more never lowers the score. With nothing observed yet the score is 50. Home, Stats, the weekly report and the IT report all use this one function. |
| Blocked (24 h) | BlockStats for the last 24 h, with the number of distinct names behind it. |
| Encryption health | Encrypted share, last 24 h. |
| Cleartext | Plain-HTTP connections to the internet in the last 24 h that went through (not to local devices, not OS checks). |
| Throughput, live connections | Bytes per second over all sessions, measured about once a second (`LiveTrafficMeter`). |
| Bell badge | Number of items in the alert inbox. |
| Cards | Behavior DNA (findings from 5.4 for hosts active in 7 days); IPv6 sessions (24 h); Kill switch (on only when Android reports Always-on VPN with lockdown); Random-looking names (5.2, 7 days); Night activity (sessions started 00:00–05:59 with the screen off, 24 h); Timer-like contacts (5.5); Large background upload (biggest upload in 24 h while the app was off screen); Network trust (5.6); Background activity (connections while the app was off screen, 24 h); Cleartext. |

### Apps

Per app, over its recorded history: connections, data, cleartext count, highest
profile risk (5.1), stalkerware score (5.7). **Privacy grade** starts at 100 and
subtracts 0.4 × highest risk (max 40), 5 per cleartext connection (max 20), 2 per
background connection (max 10) and 0.3 × stalkerware score (max 30): A ≥ 80,
B ≥ 60, C ≥ 40, D ≥ 20, otherwise F. The "high risk" badge counts apps with risk
or stalkerware score ≥ 70.

### Traffic and connection lists

Rows from `connections` (and blocked lookups from `dns_queries` where a list is
about blocking), newest first, with no row limit. A host's detail screen shows its
tracker label (5.1), its anomalies and every app that contacted it.

### Stats

Tiles for the last 24 h: connections, blocked, cleartext, data, block rate,
encryption, and the same privacy score as Home. Top blocked names come from
`dns_queries`. "Countries" uses a built-in table of 64 address ranges of large
providers (Google, Amazon, Microsoft, Meta, Cloudflare…); any other address shows
as unknown rather than guessed. The timeline mixes DNS anomalies (critical at
severity ≥ 9, high ≥ 7, else medium), blocked items and behaviour findings.
Remembered networks show their trust score.

### Alert inbox

The last 7 days of DNS anomalies and TLS alerts (JA3 threats, weak ciphers,
fingerprint changes, new certificates), plus one cleartext entry per app.

### Security analysis

Counts of every stored JA3 threat, weak-cipher and CT alert (all of them, not a
recent sample), the newest of each type, and the TLS baseline status per app
(learning, ready, alerts).

### Ad privacy

Blocked in the last 24 h and block rate (BlockStats); "N blocked at <level>" =
distinct names on the lists your level uses, plus the curated ad hosts at
Standard and Strict; per-category and per-source counts and switches; your own
domain rules; the apps that contacted the most trackers in 24 h.

### Why am I seeing this ad? / Camera & Mic Watch

Links to the ad settings of Google, Meta and others; the Ad-SDK switch; a
permission audit of apps; the sensor log from 5.8, grouped by app.

### Rules

Your rules, with hit counts. Suggestions (`app/ui/rules/RuleSuggestions.kt`) look
at the last 7 days: an app with 3 or more unencrypted internet connections gets
"Block unencrypted connections of X", an app with 100 or more background
connections gets "Block X in the background". An app you already blocked, or that
a rule already covers, gets none. Dismissed suggestions stay dismissed.

### Diagnostics (Settings)

Tunnel state, kill switch, Private DNS, a test lookup, **upstream DNS** (the
running VPN does one real lookup over its own path), external IP (opt-in, asks
api.ipify.org), DNS leak check, IPv6 packets dropped, installed apps with internet
access, packets captured.

### Payloads (enterprise only)

Inspected HTTPS requests and responses, see section 8.

### PCAP export (Home)

Raw packets in both directions (what apps send and what they receive, including
DNS answers PrivacyGuard makes) in Wireshark format. Stops at 100 MB or when
protection stops; deleted after 24 hours.

---

## 7. Privacy: what is stored, for how long, and what leaves the phone

### 7.1 Stored on the phone

Everything in 3.3. History is kept for the retention period you set (default 30
days) and capped in size. DNS anomalies, TLS alerts, sensor events and profiles
older than the retention period are deleted too. "Clear all collected data"
erases every history table, exports, PCAPs, the CT record and what the running
VPN holds in memory, then compacts the database. Settings, rules and blocklists
stay (`app/data/repository/LocalDataEraser.kt`; a test fails if a new table is not
classified).

### 7.2 What leaves the phone

Nothing is ever sent to the makers of PrivacyGuard. The app contacts:

| Destination | When | What it learns |
|---|---|---|
| Your DNS resolver (Cloudflare 1.1.1.1 by default) | Every lookup | The names apps look up. Encrypted only with DoH. |
| raw.githubusercontent.com, easylist.to, small.oisd.nl, urlhaus.abuse.ch | Blocklist download, about weekly | That a PrivacyGuard install exists, your IP address. |
| reports.exodus-privacy.eu.org | Tracker list, at most weekly | Same. |
| crt.sh | Only if Certificate Transparency is on | The base domains of servers your apps contact. |
| api.ipify.org | Only if the external-IP check is on, when you run Diagnostics | Your IP address. |
| Your SOCKS servers | Only with dual-hop | Hop 1 your IP and hop 2's address; hop 2 the destinations. |
| Your organisation's SIEM | Enterprise only, if configured | Inspected HTTPS requests and responses (section 8). |

Exports (PCAP, CSV, IT report) leave the phone only when you share them. A test
(`OutboundHostsDisclosureTest`) fails if the code contacts a host that the
onboarding text does not name.

---

## 8. Consumer and enterprise builds

| | Consumer | Enterprise |
|---|---|---|
| Monitoring, blocking, detection, all screens above | Yes | Yes |
| HTTPS inspection (payloads) | No; the code is not in the APK | Yes, opt-in |
| MDM managed configuration | No | Yes |
| SIEM shipping | No | Yes |

**HTTPS inspection** (`app/src/enterprise/java/com/privacyguard/vpn/mitm/`): the
user gives consent during onboarding and later switches inspection on in the
Payloads screen. PrivacyGuard then creates its own certificate authority, which the
user installs in Android's settings, and acts as a proxy for apps that trust user
certificates (most apps do not, since Android 7; browsers and apps that opt in
do). It records requests and responses up to a size limit, flags apps that pin
their certificates, can redact personal data, and keeps payloads for its own
retention period. Turning inspection off deletes the CA key and the stored
payloads. **Android keeps the installed certificate after PrivacyGuard is
uninstalled**; onboarding tells the user how to remove it (Settings → search
"User certificates" → "PrivacyGuard Enterprise CA" → Remove).

**SIEM**: inspected payload records are queued and sent to the configured
endpoint in batches (100 events or 60 seconds), only over HTTPS, with a bearer API
key, an optional certificate pin and an optional body signature (`SiemEndpoint.kt`). An older metadata shipper class
(`EnterpriseMetadataShipper`) exists but nothing calls it.

---

## 9. Known limits

- **Encrypted content is not visible** (consumer, and enterprise without
  inspection). PrivacyGuard sees who, when, how much and how it is encrypted.
- **Attribution is best effort.** DNS lookups are made by Android's resolver, not
  by the app, so they are credited to an app only when the app connects to that
  name within 60 seconds. Camera and mic use is credited to the app on screen,
  which can be wrong; with the screen off no app is named.
- **Apps that bring their own resolver** over TLS, QUIC or DoH to unknown servers,
  or connect to hard-coded addresses, are not filtered by DNS blocking. Only known
  DoH addresses and the standard encrypted-DNS ports are blocked.
- **First start after install:** blocking needs the lists, so the very first
  tunnel waits about half a minute. Later starts use the cache. Until the first
  download (about a minute after install), only the built-in lists apply.
- **Memory:** with all lists loaded the app uses about 200–260 MB (PSS) on a test
  phone, about 68 MB of it Java heap.
- **The VPN drop alert only alerts.** It cannot restart protection or block
  traffic, and it does not run if Android kills the app. Use Always-on VPN with
  "Block connections without VPN" for that; Settings has a button to Android's
  VPN settings.
- **Dual-hop carries TCP only** (section 4.4).
- **Rules:** exact names unless written `*.name`; no IPv6 rules.
- **Weak-cipher alerts** are about what an app *offers*, which old libraries do
  even when the server picks a strong suite.
- **JA3 threat matching** uses 35 published hashes. JA3 values are shared by many
  programs that use the same TLS library, so a match is a lead, not proof.
- **Behaviour findings are all "medium"**, and the behaviour notification fires
  only for "high", so behaviour changes appear on Home and in the inbox but never
  as a notification.
- **Countries** come from 64 built-in address ranges; most addresses show as
  unknown.
- **Network trust "No thanks"** applies to all Wi-Fi networks (no location
  permission, so networks cannot be told apart reliably).
- **TLS fingerprints:** learning covers what an app does in its first 7 days, so a
  feature used later can alert once; new fingerprints are accepted silently
  after an update, which is also when a bad SDK could arrive; QUIC is not
  fingerprinted.
- **History is written when a connection closes** (or after 5 minutes idle for
  TCP, 30 seconds for UDP), so open connections appear a little later.
- **Large DNS answers** (over 4 KB) need DNS over TCP, which the tunnel does not
  offer, so they fail.
- **Updating the app stops the VPN** unless Always-on VPN is on for it.
- **Meta app events** share `graph.facebook.com` with "Continue with Facebook",
  so they cannot be blocked at DNS without breaking login.
- **PCAP files are not encrypted** while they exist (at most 24 hours).
- **No retro-hunt** (5.9).
- **The Flutter UI** (`flutter/`) is a separate front end and is not covered by
  this document.
