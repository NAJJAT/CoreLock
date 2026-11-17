# Feature reference

Every feature of CoreLock, with the detail behind it. For a short overview see the
[README](../../README.md); for how each feature is calculated in the code, see
[HOW_IT_WORKS.md](HOW_IT_WORKS.md).

## Contents

1. [Network protection](#network-protection)
2. [Detection](#detection)
3. ["Why am I seeing this ad?"](#why-am-i-seeing-this-ad)
4. [Security systems: every number opens its list](#security-systems-every-number-opens-its-list)
5. [Reports and export](#reports-and-export)
6. [HTTPS inspection, MDM and SIEM](#https-inspection-mdm-and-siem)
7. [Languages and right-to-left layout](#languages-and-right-to-left-layout)

---

## Network protection

| Feature | Details |
|---|---|
| **Local VPN engine** | A TUN interface carries all IPv4 and IPv6 traffic (TCP and UDP). Both address families run through the same TCP state machine (`TcpForwarder`, with flow control and retransmission toward the app) and UDP forwarder (oversized replies are fragmented). The tunnel only opens once rules and blocklists are loaded. Every finished flow is written to history with its app, hostname, bytes and timing. |
| **DNS filtering** | Every lookup goes through `DnsHandler`: blocklists (HaGeZi and OISD domain lists, held as sorted 64-bit name fingerprints, ADR 0014), your own rules, and the per-app ad-SDK policy. A user Allow beats ad, tracker and telemetry lists and the ad-SDK policy; malware and phishing hosts stay blocked unless the user accepts a warning. Blocked names get *NXDOMAIN*, so no connection is made; so do answers whose CNAME points at a blocked name. The TLS SNI and QUIC SNI of each connection get the same decision, which also stops apps with their own resolver or hard-coded addresses when they send a server name. Lookups go only to the resolver you chose (and that provider's second address); if it cannot be reached the app gets *SERVFAIL*. |
| **DNS over HTTPS** | Optional RFC 8484 DoH to Cloudflare, Google or Quad9. Connects to the provider's literal bootstrap address so the DoH host lookup cannot loop through the tunnel. If DoH fails the lookup is answered with *SERVFAIL*; it is never resent as plain DNS (ADR 0012). |
| **Encrypted-DNS bypass guard** | Drops DNS-over-TLS/QUIC and DoH to known resolver IPs from other apps, so they fall back to DNS that the filter can see. |
| **Filter rules** | Block by domain, IPv4 address or range, app or globally, optionally "only in the background". Three protection levels (Minimal, Standard, Strict). A Block rule for a name also blocks its subdomains; an Allow rule matches only the exact name, or `*.name` for every subdomain (ADR 0017, since 1.2.1). When two of your rules conflict, the more specific one wins (one app before every app, an exact name before a wildcard); between equally specific rules, Block wins. Rules apply instantly without restarting the VPN. Hit counters and suggested rules. |
| **Kill switch** | Uses Android's *Always-on VPN* with *Block connections without VPN* (only Android can block once the tunnel is gone). The card shows "blocking" only when Android reports lockdown, and "not checked" while protection is off or before Android 10 (Android tells only the running VPN). There is no in-app kill switch. |
| **Cleartext / weak TLS** | Detects plain-HTTP connections and weak TLS versions and cipher suites (NULL, EXPORT, RC4, DES/3DES, anonymous). Optional rules block them. Since 1.2.1 the site name of a plain-HTTP request gets the same block decision as a TLS server name. |
| **Network trust** | Scores each network from network-side signs only: open Wi-Fi, captive portal, tampered DNS answers, DNS hijacking, TLS interception. On a low-trust Wi-Fi it suggests Strict mode. |
| **Dual VPN** | Optional two-hop SOCKS5 chain. Carries every TCP connection, and DNS when DoH is on. QUIC is dropped while it is on so apps use TCP; other UDP goes direct unless "Block UDP outside the chain" is on. |
| **Quick Settings tile and boot start** | VPN toggle in the notification shade; restarts after reboot if it was on. |

## Detection

| Feature | Details |
|---|---|
| **TLS fingerprinting (JA4 + JA3)** | Computed once per connection from the full, reassembled ClientHello. No decryption, no MITM. Each app gets its own baseline, learned over its first **7 days**. After an app update (version code), an Android system update, or a WebView/Conscrypt update, new fingerprints are accepted silently for 24 h. An alert fires only when the **same app** shows an **unknown fingerprint** **outside** that window. The alert says what changed, possible reasons (hidden SDK, impersonation, interception tool, harmless library change) and what to do, with a *Trust* action. Stored fingerprints not seen for 180 days are removed, at most 50 per app (since 1.2.1). |
| **JA3 watch-list** | 35 JA3 hashes published for known tools. JA3 collides across clients, so a match is a hint, not proof, and the UI says so. |
| **DNS anomalies** | Random-looking (DGA-like) names, tunnelling-like labels, bursts of lookups and repeated beacon-like lookups. |
| **Behavior DNA** | Learns each app's normal rhythm (destinations, hours, upload size, background beacons) and flags changes after the learning period. |
| **Permission mismatch** | Flags apps whose traffic doesn't fit their permissions (e.g. location services without location permission, tracker traffic from a utility app). |
| **Stalkerware signals** | No launcher icon, sideloaded, background location, mostly-background traffic, large background uploads. |
| **Certificate Transparency** | Opt-in. Checks crt.sh for newly issued certificates for domains you contact. |
| **Tracker database** | Curated tracker SDKs (company, category, hosts, class patterns), plus the Exodus list. The APK scanner finds embedded SDKs from DEX strings without running the app. |

## "Why am I seeing this ad?"

An honest explanation screen (Settings › Advanced › Analysis › Ad privacy). It does
**not** claim to remove ads inside Facebook or Instagram.

| Part | What it does |
|---|---|
| **Camera & Mic Watch** | A timeline of microphone and camera use with time and screen on/off, plus the search *"Did any app use my mic between [time] and [time]?"*. Android does not say which app holds the mic, so the app shows the app that was on screen ("likely app"), or "unknown". |
| **Location audit** | Lists apps with *Always* vs *While in use* location access, with one tap to each app's permission page. Background location exists only on Android 10+. "Last background use" per app is a system-only API, so the screen links to Android's Privacy dashboard. |
| **Block ad/tracking SDKs in other apps** | Per-app DNS policy for Meta Audience Network, AppsFlyer, Adjust, Branch and others. Login, push and deep-link hosts are never blocked (`graph.facebook.com`, `firebaseinstallations.googleapis.com`…), so "Continue with Facebook" keeps working. Exceptions are listed on screen. |
| **Reduce ad tracking checklist** | Deep links to Android advertising ID, Meta *Your activity off Meta technologies*, Google *My Ad Center*, Google *Timeline* and mic permission review. Done steps are remembered. |
| **Explainer** | A short plain-language explanation of how ad targeting works and what the app can and cannot change. |

## Security systems: every number opens its list

- **Same list behind every number.** (Settings › Advanced › Analysis › Security systems,
  the 1.0 Home.) Each number and card there is computed from the list it opens, for the
  same time window, anchored at the same moment (`HomeEvidence`). No `LIMIT` anywhere.
  The list count equals the number shown.
- **What opens what:**
  - *Blocked today* (since midnight, the same count as Home, Activity, Apps, Statistics and
    the notification: `BlockStats.today`) opens the Activity tab's Blocked list;
    *Unencrypted (24 h)* and *Encryption health* open their lists.
  - The security cards (behavior changes, random-looking domains, night activity,
    repeating patterns, uploads, background connections, IPv6 sessions) open their lists.
  - The weekly report (connections, blocked, behavior changes, data) opens its lists.
  - The alert bell shows exactly the inbox count ("99+" above 99).
- **While you slept:** "idle" means the screen was off. Apps are ranked by DNS lookups
  made while the screen was off; the definition is on screen. Tapping an app opens its
  traffic for that window, idle-only.
- **Per-app traffic**, in three tabs:
  - *By purpose:* domains grouped as e.g. "Instagram core", "Meta ads", "Braze
    analytics", "CDN", "Other, owner unknown", each with a one-line explanation.
  - *Every connection:* time, domain, company, purpose, source (**TLS SNI**, **DNS** or
    **IP only**), bytes sent/received, screen on/off, foreground/background,
    allowed/blocked, and **why it was flagged**.
  - *Raw DNS lookups.*
- **Domain detail:** owner company, purpose and tracker-database entry, frequency over 7
  days, the apps that used it, first/last seen and bytes. It says plainly that we see
  destinations and sizes, not encrypted content.
- **Empty states say why:** protection off, app not opened (needs Usage Access), nothing
  while the screen was off, traffic not attributable to an app, or history doesn't reach
  back that far.
- **Friendly names:** app labels and icons everywhere, never package names ("Removed app"
  or "Not attributable to an app" when there is no label).

## Reports and export

- **PCAP export** (Settings › Advanced › Analysis › Statistics): real PCAP files
  (LINKTYPE_RAW) for Wireshark/tcpdump, both directions. Stops at 100 MB or when
  protection stops; not encrypted, deleted after 24 hours. Starting or sharing a capture
  requires device authentication.
- **CSV export:** 7 days of connections (app, domain, IP, bytes, TLS, background,
  blocked). Like every export, it asks for the phone's screen lock first. Every cell is
  escaped against spreadsheet formulas (`ConnectionsCsv`, `CsvCell`).
- **IT report:** PDF + JSON summary for IT staff (kept in English on purpose).
- **Weekly report:** WorkManager summary notification.

## HTTPS inspection, MDM and SIEM

What inspection can and cannot do, in plain words, is in the README
([HTTPS inspection](../../README.md#https-inspection-what-it-can-and-cannot-do)). The
technical detail:

- **HTTPS payload inspection (MITM).** Off by default and gated by explicit consent:
  - The app generates a local CA only once inspection is on and consented to; on
    Android 11+ the user installs it in Settings.
  - It forges per-host leaf certificates and logs requests and responses.
  - Apps that pin certificates or ignore user CAs are passed through as metadata-only,
    and the UI says why.
  - *Block QUIC* drops QUIC handshakes on UDP/443 so browsers fall back to TLS over TCP;
    other UDP/443 (calls, VPNs) is not affected.
  - Turning inspection off deletes the signing key and tells the user how to remove the
    installed CA.
  - At most 32 connections are decrypted at the same time; more pass through undecrypted
    and their server name is still checked (since 1.2.1).
- **PII redaction:** emails, card numbers, phone numbers, JWTs, bearer tokens and
  sensitive JSON fields, in headers and text bodies. Values in URL query strings (also in
  Referer and Location headers) and in HTML form bodies become `[REDACTED]`; a name is
  kept only when it looks like a field name and holds no personal data (otherwise it is
  replaced too); bodies that are not text (images, files, compressed or multipart data,
  HTTP/2 frames) are kept as type and size only. Anything sent to the SIEM is always
  redacted this way. The local payload log is redacted the same way while
  `mitm_redact_pii` is on (the default); with it off, the inspector stores exactly what
  left the phone.
- **SIEM shipping** happens automatically once 100 events are queued or a minute after
  the first one, and "Ship now" sends the same queue. Before each upload CoreLock checks
  again that inspection, consent and shipping are on; after a failed upload it waits
  30 seconds, then twice as long each time, up to 30 minutes.
- **Consent from MDM** (`mitm_consent_given`) is valid for as long as the policy asserts
  it (a user's own consent is asked again after 90 days); setting it to false, or
  removing the managed configuration, withdraws it and stops inspection. Removing the
  configuration also switches off inspection the MDM turned on; the user's own choices
  stay.
- **Automatic CA install** (`ca_install_automatic`, device or profile owner only) happens
  only while inspection is on and consented to.
- **MDM / managed configuration** (`application_restrictions.xml`):

| Key | Purpose |
|---|---|
| `mitm_enabled`, `mitm_consent_given`, `ca_install_automatic` | Enable inspection and consent; automatic CA install on managed devices |
| `mitm_redact_pii`, `mitm_retention_days` | PII redaction and payload retention |
| `mitm_ship_to_siem`, `mitm_siem_endpoint`, `mitm_siem_api_key` | SIEM forwarding (HTTPS) |
| `mitm_siem_signing_key`, `mitm_siem_pin_sha256` | Payload signing and certificate pinning for the SIEM endpoint |

A Device Admin receiver supports Intune, Workspace ONE, Jamf and similar.

## Languages and right-to-left layout

- English, **Nederlands**, **Français** and **العربية**, with proper plurals (French
  `one/many/other`, Arabic all six forms).
- **Language picker** in Settings › Language: System default, English, العربية,
  Français, Nederlands, each named in its own language. It uses per-app locales
  (`AppCompatDelegate.setApplicationLocales`, `res/xml/locales_config.xml`, so Android
  13+ also lists the app under the phone's "App language") and
  `AppLocalesMetadataHolderService` with `autoStoreLocales` for Android < 13. The choice
  survives restarts and reboots, and switching to Arabic flips the layout to RTL at once.
- **Notifications follow the choice** while protection keeps running, also below Android
  13 where AppCompat applies it to activities only: `AppLanguage` keeps its own copy of
  the choice and gives notification code a context in that language.
- **RTL:**
  - `supportsRtl`; start/end instead of left/right.
  - Auto-mirrored directional icons; direction-neutral separators.
  - The heatmap mirrors its columns together with its labels.
- **Locale formats:** dates and times via `DateUtils`, numbers via `NumberFormat`,
  weekday names via `DateFormatSymbols`. Arabic shows Arabic-Indic digits.
- **Kept in English on purpose:** the IT report (for IT staff; PDF canvas text shaping),
  SIEM payloads, logs and brand names.
- **Native review needed:** the Arabic, French and Dutch texts are machine translations
  waiting for review ([translations](../translations/README.md)).
