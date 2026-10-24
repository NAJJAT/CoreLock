# CLAUDE.md — CoreLock (PrivacyGuard)

> Engineering handbook for Claude Code in this repository.
> Read it completely at the start of every session. It explains **what** the rules are, **why** they exist, and **how** the system works, so you can make good decisions in situations this file doesn't cover.
>
> **Priority when instructions conflict:** (1) Section 1 hard rules → (2) the owner's explicit request in chat → (3) everything else in this file → (4) your own judgment. If a chat request conflicts with Section 1, stop and ask. Never resolve that conflict silently.
>
> **First session after this file changes:** check every class name, path and command in it against the real repo and report anything wrong. This file must describe the code as it is, not as someone remembers it.

---

## Contents

1. Hard rules
2. The product and its promise
3. Threat model
4. Security architecture
5. How it works: the packet path and the four layers
6. Engineering standards
7. Data and database
8. UI, text, languages and accessibility
9. Testing strategy
10. Dependencies and supply chain
11. CI
12. Release process
13. Documentation and README standards
14. How to work with the owner
15. Report format
16. Known limitations and open backlog
17. Glossary

---

## 1. Hard rules

These exist because breaking any of them causes damage that is expensive or impossible to undo. Each rule says why.

### 1.1 Git: the owner commits, pushes and merges

- **Never** run: `git commit`, `git push`, `git merge`, `git rebase`, `git cherry-pick`, `git revert`, `git reset --hard`, `git checkout -- <file>` on files you didn't change in this task, `git stash drop`, `git clean`, `git branch -D`, `git tag`, or anything with `--force`.
- **Never** run: `gh pr merge`, `gh pr close`, `gh pr review --approve`, `gh release`, `gh repo edit`, or change repository settings, secrets or branch protection — even when `gh` is logged in.
- **Allowed (read-only):** `git status`, `diff`, `log`, `show`, `blame`, `branch --list`, `fetch`, `ls-files`, `gh pr view`, `gh run view`, `gh api` GET requests.
- At the end of a task, **propose** commits: which files go in each commit, in which order, and the message. If uncommitted changes from an earlier task touch the same files, stop and say so before starting, so work never gets mixed.
- Before ever suggesting `git add -A`, run `git status` and list every untracked or unexpected file (logs, `state/`, `local.properties`, build output, keystores).

**Why:** the owner reviews every change before it enters history. A wrong commit on `main` of a security app can ship to users; a force-push can destroy work permanently.

### 1.2 Secrets and signing

- Never create, read, print, copy, move or commit a keystore, `release-signing.properties`, a password, an API token or a private key. The only signing file in git is `release-signing.properties.template`.
- The release build **must fail** when no real signing key is configured. Never add a fallback to the debug key.
- If a release build must be tested, use a throwaway key created **outside** the repo, then delete the key and the APK, and say in the report that you did.

**Why:** Android only installs an update if it's signed with the same key as the installed app. A leaked key lets anyone ship a "CoreLock update" that steals all traffic. A lost key means no user can ever update. A debug-key fallback could silently publish an APK nobody can update.

### 1.3 The privacy promise is a product requirement

- **No** analytics, telemetry, crash-reporting SDKs, ad SDKs, remote config or "phone home" of any kind.
- Every host the app contacts (blocklist sources, mirrors, DoH resolvers) must be listed in the README and docs network tables. `OutboundHostsDisclosureTest` enforces this. Never weaken, skip or delete that test; update the documentation instead and tell the owner.
- Connection history, rules and logs stay on the device, inside the encrypted database.

**Why:** the app sees every domain the user visits. Its connection log is effectively a full browsing history. If the app itself leaks, it becomes the exact threat it claims to protect against, and trust is the whole product.

### 1.4 Dependencies

- Never add, remove or upgrade a dependency without asking. In the request, say: why it's needed, what it costs in APK size, its license, who maintains it, and whether it touches the network.
- Never regenerate `gradle/verification-metadata.xml` wholesale and never disable, lenient-mode or loosen verification. See §10.
- Dependency updates (including Dependabot PRs) are never mixed into feature or fix work.

**Why:** every library runs with the app's full permissions inside the VPN process. A compromised or careless dependency sees all traffic.

### 1.5 Scope and honesty

- Do what was asked. Report other problems you find; don't fix them silently.
- If an instruction is technically wrong, push back **with evidence** before doing it. Being correct matters more than following the letter of a prompt. Past examples: narrowing the QUIC gate so STUN/WireGuard/DTLS weren't broken, and proving the CI failure came from the verification file, not from `setup-gradle`.
- Never claim something works without having run it. Clearly separate **verified** from **assumed**.

---

## 2. The product and its promise

**CoreLock** (internal name and package: `com.privacyguard.app`, app name *PrivacyGuard*) is an Android firewall and privacy tool that works **without root**, using Android's `VpnService` to route the device's traffic through the app on the device itself. No traffic is sent to any server we operate — we don't have any.

What it does for the user:

- Blocks ads, trackers, malware and phishing domains (DNS and connection level).
- Stops apps from bypassing filtering with encrypted DNS.
- Lets the user block apps completely or in background only.
- Shows which app connects where, and what stopped each blocked connection.
- Security monitoring: cleartext and weak TLS detection, DNS anomaly detection, TLS fingerprint changes, sensor and permission auditing, APK scanning.

Facts that shape every decision:

| Fact | Consequence |
|---|---|
| **Not distributed on Google Play.** APKs are published on GitHub Releases. | Full ad blocking is allowed; no Play policy workarounds. No automatic update check, by design: Settings › About opens the GitHub releases page, so no extra server is contacted. Users must trust our signing key (§12). |
| **One app.** The only flavor is `enterprise` (application ID `com.privacyguard.app.enterprise`, version shown without suffix). It includes consented HTTPS inspection (MITM), MDM and SIEM export, and it is what 1.0.0 releases. The consumer flavor was removed on 2026-10-23 (owner's decision). | HTTPS inspection now ships to every user, so its code is security-critical for everyone (§3.1, §4.2). It stays in `app/src/enterprise/`; the shared engine in `app/src/main/`. Don't reintroduce a second flavor without the owner. |
| **Kotlin 2.2, Compose, Room + SQLCipher, WorkManager, AGP 9.2.0, minSdk 24, targetSdk 36.** | Code must work from Android 7.0 to the newest release. Check API levels for every platform call. |
| **Languages:** English (default), Arabic (RTL), French, Dutch. | Every user-visible string exists in all four. |
| **Release ABIs:** `arm64-v8a` and `armeabi-v7a`. | No per-device splits (no Play), so 32-bit phones must keep working. |
| **Android only.** Flutter, iOS, Rust, `testapp`, NativeBloom and `Ipv6Proxy` were removed deliberately. | Don't reintroduce them. Old code lives on branch `archive/before-cleanup`. |

**Owner's stated priority: quality and performance over effort.** When choosing between a quick fix and the correct fix, choose the correct one and explain the extra cost.

---

## 3. Threat model

Use this when designing or reviewing any change. If a change creates a new asset, adversary or trust boundary, say so in the report and propose an update to this section.

### 3.1 Assets (what we protect)

| Asset | Class | Why it matters |
|---|---|---|
| Connection log (app, domain, IP, time, SNI) | **Sensitive** | Reveals browsing, health, religion, politics, relationships. |
| User rules (Allow/Block) and settings | Personal | Reveal habits; tampering silently disables protection. |
| The device's traffic in transit through the tunnel | **Sensitive** | Everything passes through our process. |
| Database encryption key | **Secret** | Unlocks all of the above. Keystore only. |
| Release signing key | **Secret** | Controls who can update the app on every device. |
| Blocklists | Integrity-critical | A poisoned list can block the bank or allow malware. |
| HTTPS-inspection CA private key | **Secret** | Shipped to every user since 2026-10-23. Once the user installed the CA, the key can decrypt that phone's HTTPS for apps that trust user CAs. Must never leave the device. |
| Payload log (decrypted HTTPS content) | **Sensitive** | The most sensitive data the app can hold; exists only while inspection is on. |

### 3.2 Adversaries

1. **Ad and tracker networks** — try to evade blocking (DoH, hardcoded resolvers, QUIC, CNAME cloaking, same-host serving).
2. **Malicious apps on the same phone** — try to disable protection, abuse exported components, read our data, or talk to local listeners.
3. **Network attackers** (public Wi-Fi, hostile ISP) — tamper with downloads (blocklists, tracker list), inject DNS.
4. **Compromised list sources or mirrors** — supply chain.
5. **Someone with physical access** to an unlocked phone — opens the app and reads history.
6. **Malformed or hostile packets** — crash or hang the engine, which takes down the user's entire connectivity.
7. **Compromised dependency or build pipeline** — ships malicious code inside a legitimately signed APK.

Out of scope (state it honestly in docs): a rooted or fully compromised device, OS-level malware, and traffic of apps excluded from the VPN.

### 3.3 Trust boundaries

- TUN interface ↔ our engine: **every byte is untrusted.**
- Our app ↔ other apps: intents, exported components, local sockets, clipboard.
- Our app ↔ the internet: list downloads, DoH upstream (there is no update check).
- Our app ↔ storage and backups.
- Our code ↔ third-party libraries.
- Build machine ↔ CI ↔ released APK.

### 3.4 Main threats and required mitigations (STRIDE)

| Threat | Mitigation in this codebase |
|---|---|
| Parser crash/hang from crafted packets (DoS) | Bounds-checked parsers, hard caps, no exceptions on the hot path, fuzz/property tests (§6.3). |
| Ads evading DNS blocking (tampering) | Layers 2 and 3: DoH/DoT blocking, SNI and QUIC filtering, CNAME checks (§5). |
| Poisoned or oversized blocklist | HTTPS sources only, size/line limits, strict parsing, last-known-good kept, built-in fallbacks (§5.6). |
| Other apps toggling protection or reading data (elevation) | `exported="false"` by default, `BIND_VPN_SERVICE` on the service, no unauthenticated local listeners (§4). |
| History leaked via backup, logs, screenshots (disclosure) | Encrypted DB, backup exclusion, no sensitive data in logs (§4, §7). |
| Formula injection in CSV export | Escaping of all attacker-controlled fields, tested through the real export code (§4). |
| Malicious update / key compromise (spoofing) | Signing key never in repo, published fingerprint, release checksum (§12). |
| Supply-chain compromise | Pinned versions, Gradle dependency verification, OSV scan, secret scan in CI (§10, §11). |
| HTTPS inspection abused: CA key leaves the phone, consent skipped, or inspection switched on by another app (disclosure, elevation) | Off by default; consent in-app or via MDM only; CA key never exported; key deleted when turned off. Reviewed in step 3 of the single-app change (2026-10-23). |
| Protection silently stopping | Notify the user when the tunnel drops; kill switch via Android's Always-on VPN + lockdown (§5.7). |

---

## 4. Security architecture

### 4.1 Principles

1. **Defense in depth.** No single layer is trusted to catch everything; that's why blocking has four layers.
2. **Least privilege.** Request the minimum permissions; justify each one in the README permissions table. Components are not exported unless they must be.
3. **Secure by default.** New settings default to the safer option unless that breaks normal use; say which you chose and why.
4. **Fail closed in filtering, fail visibly in the service.** When the user chose STANDARD or STRICT and we can't read a connection's destination (unreadable QUIC Initial, incomplete ClientHello after the limits), we drop rather than let it through. When the engine itself stops, we never pretend it's still protecting: the notification and dashboard must show the true state.
5. **Untrusted input everywhere.** Packets, downloads, intents, imported files and database rows written from network data are all attacker-controlled.
6. **No custom crypto.** Android Keystore, SQLCipher, the platform TLS stack. Nothing hand-made.
7. **Honest limits.** Never claim protection we can't deliver. Limits go in the UI and release notes (§16).

### 4.2 Controls (OWASP MASVS areas)

**Storage**
- Database encrypted with SQLCipher; the key is protected by the Android Keystore and never logged or exported.
- Sensitive data is excluded from Android backups (`dataExtractionRules` / `fullBackupContent`, or `allowBackup="false"`). Verify this in the merged manifest when touching the manifest.
- Nothing sensitive on shared/external storage. Exports go through the system file picker or `FileProvider` with narrow paths.
- Log retention: old connection rows are purged automatically. Don't add tables that grow forever.

**Crypto**
- No `Random()` for security (use `SecureRandom`), no ECB, no MD5/SHA-1 for security, no hardcoded keys or IVs.

**Network (the app's own traffic)**
- HTTPS only. No `cleartextTrafficPermitted`. No custom `TrustManager` or `HostnameVerifier` outside the HTTPS-inspection engine (`app/src/enterprise/.../vpn/mitm/`), and none there for the app's own outbound connections.
- Our own sockets are protected (`VpnService.protect()` or excluded) so they never loop back into the tunnel.
- Downloads have size limits and timeouts.

**Platform**
- Every component is `exported="false"` unless another app genuinely needs it. The VPN service requires `android.permission.BIND_VPN_SERVICE`.
- No component lets another app start or stop protection or change rules.
- `PendingIntent` uses `FLAG_IMMUTABLE` unless mutability is required, always with explicit intents.
- Never launch an `Intent` taken from another intent's extras.
- **No WebView.** The payload inspector is Compose (`PayloadInspectorUi.kt`) on purpose: it removed XSS and JavaScript-bridge risk. Don't bring WebView back.
- Debug-only components (e.g. `DebugHooksReceiver`) must not appear in the merged **release** manifest.
- Never open a listener on `127.0.0.1` or `0.0.0.0` that other apps could talk to.

**Code**
- Room with parameterized queries only; no SQL string building.
- CSV export: every attacker-controlled field (app name, package, SNI host, domain) is escaped against formula injection (`=`, `+`, `-`, `@`, tab, carriage return). `ExportInjectionTest` must run through the real `ConnectionsCsv` code, never a copy.
- Release builds: minify and shrinkResources on, not debuggable, no verbose logging, no debug endpoints.

**Privacy**
- Data minimization: store only what a screen actually uses.
- Permissions are explained in the README with a reason for each one.

**HTTPS inspection (MITM)**
- Off by default. Only with explicit, informed consent from the user, or from the organisation's MDM on a managed phone (`mitm_consent_given`). Nothing may turn it on or record consent from outside the app or MDM.
- Be honest about reach: most apps on Android 7+ ignore user CAs or pin certificates; they are shown as metadata only. Docs and UI must say so.
- Any change here gets an extra security review section in the report.

### 4.3 Red-flag scan

Run before finishing any change that touches security-relevant code, and report hits:

```
TrustManager  HostnameVerifier  ALLOW_ALL  cleartextTrafficPermitted  http://
exported="true"  getParcelableExtra(...) → startActivity  FLAG_MUTABLE
addJavascriptInterface  setJavaScriptEnabled  WebView
Log.d/Log.i/Log.v with domains, IPs, packages or keys
"AES/ECB"  "MD5"  "SHA1"  java.util.Random near crypto
rawQuery or SQL built with +   fallbackToDestructiveMigration
catch (e: Exception) { }   (empty catch)
```

---

## 5. How it works: the packet path and the four layers

### 5.1 The packet path

```
Apps ──► Android ──► TUN interface (VpnService)
                         │
                   PrivacyVpnService reads raw IP packets
                         │
          ┌──────────────┼──────────────────┐
       UDP/53          TCP               UDP/443 and other UDP
     DnsHandler     TcpForwarder          UdpForwarder + QuicGate
          │              │                       │
   Layer 1 + 2     Layers 2, 3, 4          Layers 2, 3, 4
          │              │                       │
   answer or       forward through         forward, hold until the
   NXDOMAIN        a protected socket,     ClientHello is readable,
                   or reset                or drop
                         │
             record: connection row + BlockSource + counters
```

Owner attribution: the app that owns a connection is looked up by UID (cached per flow). At the DNS layer the owner is usually Android's own resolver, not the app; that's why per-app decisions are only exact at the connection layer (§16).

### 5.2 The four layers

| # | Layer | What it does | Main code | Why it exists |
|---|---|---|---|---|
| 1 | **DNS blocking** | Answers NXDOMAIN for blocked names; checks CNAME chains | `DnsHandler`, `DnsBlockDecision` | Cheapest and stops most ads and trackers before any connection exists. |
| 2 | **DNS-bypass prevention** | Drops DoT (port 853); blocks known DoH servers by domain and IP; forces apps through the chosen resolver | `DnsBypassGuard` | Without it, any app using encrypted DNS skips layer 1 completely. |
| 3 | **Connection filtering by SNI** | Reads the server name from the TLS ClientHello (TCP) and the QUIC Initial (UDP); resets blocked connections | `TcpForwarder`, `QuicGate` | Catches hardcoded IPs, DNS cache hits and anything that slipped past DNS. |
| 4 | **Per-app firewall** | Blocks an app entirely or only in background | firewall rules, `AppTracker` | Some apps can't be fixed by domain blocking; the user decides. |

### 5.3 One decision for every name

All name decisions — DNS, CNAME, TCP SNI, QUIC SNI — go through **`NameBlockPolicy`**. The order is fixed:

1. **User Block** rule → blocked
2. **Malware/phishing lists** → blocked, unless the user Allow rule has `overridesSecurity` (set only when the user confirmed the warning shown while creating it)
3. **User Allow** rule → allowed
4. **Ad/tracker lists + `AdSdkPolicy`** → blocked or allowed

**Why this order:** the user's explicit Block always wins. Malware sits above user Allow so a mistaken Allow can't expose the user to a known phishing site without a warning; only an informed, confirmed choice (`overridesSecurity`) lets it through. Ads sit below user Allow so the user can always fix a broken site.

**Which user rule applies** (`FilterEngine`, `ORDER`): all user rules share `HIGH_PRIORITY`; then the more specific rule wins (app + destination before app only before destination only; exact name/IP before wildcard/range, longer before shorter; more conditions before fewer); between equally specific rules, **Block wins** (owner's decision, 2026-10-23). Every screen that creates a user rule must use `HIGH_PRIORITY`, or that rule silently loses to every matching Allow.

Invariants:
- **Never add a second matching path.** If DNS and SNI disagree, a name blocked in one layer leaks through the other. The property test (fixed seed `20261021`) checks DNS and SNI against a model of this order; keep it, and keep its seed.
- **Normalize every name** before matching: lowercase, remove the trailing dot. `ads.example.com.` once bypassed user rules because of a trailing dot. This also applies in `FilterRule.domainMatches`.
- **Every block records a `BlockSource`** (DNS, SNI, QUIC, DOT, DOH, …) and is counted, so "Stopped by" in the connection details is always true.

### 5.4 QUIC (`QuicGate`)

- QUIC (HTTP/3) runs on UDP/443. Its first packet contains a readable ClientHello, but it can span several datagrams.
- At STANDARD and STRICT, datagrams of a new QUIC flow are **held** until the ClientHello is complete, then decided like TCP SNI. Unreadable QUIC long-header Initials are dropped (fail closed); the browser then falls back to TCP, where layer 3 can read the SNI.
- Hard limits: **128** pending flows, **512 KB** held in total, **10 s** abandon timeout. Over a limit, the flow is dropped and counted. **Why:** without limits, a flood of half-open QUIC flows would exhaust memory.
- Setting "Block QUIC for better filtering": default ON only at STRICT; an explicit user choice always wins over the level default.
- **Never affect non-QUIC UDP/443** (STUN/TURN for calls, WireGuard, DTLS). Breaking video calls to block ads is not acceptable.

### 5.5 DNS bypass details

- Port 853 (DoT) is dropped and recorded as `BlockSource.DOT`.
- DoH servers are blocked by domain (HaGeZi DoH list) and by IP (HaGeZi DoH-IP list), with a built-in fallback list when downloads fail.
- Downloaded DoH **IPs** are decided at the ClientHello, not at the TCP SYN. **Why:** many DoH IPs are shared CDN addresses; deciding at SYN would block unrelated sites.
- A user Allow rule overrides a DoH block (the user may run their own DoH server).
- If Android's **Private DNS** is set to a specific hostname, it bypasses our resolver. The app warns on the dashboard and before starting protection.

### 5.6 Blocklists (supply chain)

| Level | Lists |
|---|---|
| MINIMAL | HaGeZi TIF mini (malware/phishing) |
| STANDARD | + HaGeZi Pro mini |
| STRICT | + HaGeZi Pro + OISD small (and QUIC blocking on by default) |
| All levels | HaGeZi DoH + DoH-IP lists for layer 2 |

Rules:
- HTTPS only, from an allow-listed set of sources; jsDelivr is the fallback mirror.
- Enforce size and line limits; parse strictly; skip invalid lines; never crash on garbage.
- **Replace an old list only after the new one downloaded and parsed successfully.** Keep last-known-good.
- Every list has a built-in fallback so a first start without network still protects.
- Swapping lists at runtime is atomic (`FilterEngine.setRules`); a lookup never sees half a list.
- Removed on purpose: StevenBlack, EasyList, EasyPrivacy, HaGeZi Light, URLhaus (dead). Don't re-add without discussing.
- Lists are data, never code.

### 5.7 Service lifecycle

- The VPN runs as a foreground service (`specialUse` type on API 34+) with a persistent notification.
- `onRevoke()` (user stops it, or another VPN starts): stop cleanly, update the UI, don't fight the user.
- Network changes (Wi-Fi ↔ mobile, airplane mode) must not leave dead sockets.
- **Kill switch** depends on Android's *Always-on VPN* with *Block connections without VPN*. Only Android can block traffic once our tunnel is gone; the app shows "blocking" only when Android reports lockdown is on.
- OEM battery optimization can kill the service. Settings › Diagnostics shows the battery state and opens Android's battery settings (`BatteryCheck`); it does not request the exemption directly.
- Test captive portals (hotels, airports): users must be able to log in.

---

## 6. Engineering standards

### 6.1 Code style

- Idiomatic, null-safe Kotlin. No `!!` on data from outside the process.
- Structured concurrency: coroutines tied to a scope; no `GlobalScope`; no `runBlocking` on the main thread or the packet thread.
- Comments explain **why**, not what. Add a short comment for every non-obvious limit or security decision, with the reason.
- Small, focused functions; pure decision logic separated from Android APIs so it can be unit tested on the JVM.
- **No empty `catch` blocks.** Log (without sensitive data), count, or surface the error.

### 6.2 Performance (the hot path)

The packet path runs for every packet of every connection on the phone. A few microseconds per packet is the difference between invisible and "the internet feels slow", and between a normal battery day and an angry user.

- In `vpn/forwarder/`, `QuicGate`, `DnsBypassGuard`, the SNI parser and `NameBlockPolicy`: **no** per-packet allocation, logging, string formatting, regex, reflection or database access. Reuse buffers; cache per flow (e.g. owner UID).
- Never block the packet thread on I/O or on locks the UI might hold.
- Statistics are aggregated in memory and written to Room in batches.
- **Measure before and after** any hot-path change and report both numbers (µs per decision or per connection). Current reference: SNI check ≈ 4.3 µs per connection. A regression over ~10% needs a written justification.
- **Memory:** blocklists dominate the heap (measured: MINIMAL ≈ 23 MB, STANDARD ≈ 31 MB, STRICT ≈ 44 MB). Don't add per-name objects. The planned direction is compact storage (64-bit fingerprints in a sorted `LongArray`).
- **UI:** observe Room `Flow`s. Don't add `while(true)` polling loops.
- APK size is watched (release ≈ 6.1 MiB measured earlier for the old consumer build; a consumer release measured 7.13 MiB on 2026-10-23; re-measure the enterprise release before 1.0). Report the size when a change could affect it.

### 6.3 Parsers (DNS, TLS ClientHello, QUIC, IP)

Every parser handles hostile input:
- Bounds-check before every read. Use length fields only after validating them.
- DNS: cap compression-pointer jumps and reject loops; labels ≤ 63, names ≤ 255; handle truncation.
- TLS: extensions in any order, GREASE values ignored, don't assume a fixed layout. Reassemble split ClientHellos up to a hard cap.
- IPv4/IPv6: validate header lengths; bound the IPv6 extension-header walk; handle fragments.
- Return a typed "invalid" result instead of throwing; count invalid inputs.
- No recursion or allocation size driven directly by an untrusted length field.

---

## 7. Data and database

A broken migration deletes the user's rules and history. Treat schema changes as high risk.

- Every schema change: bump the version, write an explicit `Migration`, commit the exported schema JSON under `app/schemas/`, and add a test in `AppDatabaseMigrationTest`.
- `fallbackToDestructiveMigration` is forbidden; the only exception is `fallbackToDestructiveMigrationOnDowngrade`. A missing migration must crash, not wipe — in debug builds too.
- New columns on existing tables are nullable or have a default, so old rows stay valid (example: `connections.blockSource`, migration 12→13).
- In the report, state which migration paths only **compiled** and which actually **ran** on a device or emulator.
- Settings changes must keep existing users' explicit choices (a new default must not overwrite a value the user set).
- No table grows without a retention rule.

---

## 8. UI, text, languages and accessibility

- **No hardcoded user-visible text.** Every new or changed string goes into `values`, `values-ar`, `values-fr` and `values-nl` in the same change.
- Mark machine-made translations in the report and add them to `docs/TRANSLATION_REVIEW_*.md` so a native speaker can check them. Arabic is reviewed by the owner; French and Dutch need native speakers.
- **RTL:** use `start`/`end`, never `left`/`right`; mirror directional icons; test long translations (French and Dutch are often 30% longer).
- **Accessibility:** content descriptions on icons, touch targets ≥ 48 dp, sufficient contrast, works with large font sizes and TalkBack.
- **Honest UI:** never show "protected" when the tunnel isn't running. Show what was blocked and why ("Stopped by").
- **Warnings come before reliance:** Private DNS in hostname mode, missing Usage Access (needed for background-only rules), battery optimization — shown when the user enables the feature, not buried in settings.
- Every screen handles loading, empty and error states.

---

## 9. Testing strategy

### 9.1 Commands (match CI)

```bash
# Unit tests
./gradlew --no-daemon :app:testEnterpriseDebugUnitTest

# Lint
./gradlew --no-daemon :app:lintEnterpriseDebug

# R8 / shrinking
./gradlew --no-daemon :app:minifyEnterpriseReleaseWithR8

# Instrumented tests incl. Room migrations (emulator only: it uninstalls the app)
./gradlew --no-daemon :app:connectedEnterpriseDebugAndroidTest
```

### 9.2 Definition of done

1. Unit tests pass; report counts (tests / failures).
2. Lint: 0 errors.
3. `androidTest` code compiles; migration tests run if the schema changed.
4. New behaviour has tests. **For every bug fix, prove the test catches the bug:** temporarily remove the fix, run, see it fail, restore, and say so in the report.
5. Hot-path change: before/after measurement.
6. Strings in all four languages.
7. Red-flag scan (§4.3) clean, or hits explained.

### 9.3 Kinds of tests

| Kind | Used for |
|---|---|
| Unit (JVM) | Decision logic, parsers, rule matching, CSV escaping. |
| Property tests (fixed seed) | DNS vs SNI consistency, rule precedence, normalization. |
| Malformed-input tests | Every parser: truncated, oversized, looping, random bytes. No crash, no hang. |
| Security tests (`sectest/`) | Formula injection, disclosure of outbound hosts, exported components. |
| Instrumented | Room migrations, service start/stop, Keystore. |
| Manual device checks | Real apps at each level (Chrome, YouTube, messaging, video calls), Wi-Fi ↔ mobile switch, captive portal, Private DNS modes, battery and memory (`adb shell dumpsys meminfo com.privacyguard.app.enterprise`). |

Tests never touch the real network. Use fixtures.

---

## 10. Dependencies and supply chain

- Versions live in the version catalog (`gradle/libs.versions.toml`), pinned. No dynamic versions (`1.+`, `latest`).
- **Gradle dependency verification is mandatory.** When Gradle reports a missing artifact:
   1. Add only the entries Gradle reports.
   2. Check each against Maven Central (sha256 **and** sha1) and verify its PGP signature against the publisher's known key.
   3. Re-run with an **empty Gradle cache**. A warm cache hides missing entries — this exact mistake broke CI before.
   4. Report every added entry with its source.
- Dependabot PRs are reviewed by the owner one at a time, after a release, never bundled with feature work. Kotlin, KSP and the Compose compiler move together.
- Before proposing a new dependency, answer: Can the platform or existing code do this? What does it add to the APK? Does it make network calls? Does it touch packet data?

---

## 11. CI

Workflow: `.github/workflows/ci.yml`. Required checks on `main`:
1. Unit tests + lint + R8
2. OSV dependency scan
3. Room migration and instrumented tests (emulator)
4. Secret scan (gitleaks)

Rules:
- A red CI is a stop sign. Nothing merges on red.
- When CI fails, **read the actual log first.** Reproduce locally (ideally with an empty cache), prove the root cause, then propose the smallest fix. Don't revert, pin or disable on a guess.
- If you can only test on Windows and CI runs Linux, say so.
- `gradlew` must stay executable (`100755`). On Windows: `git update-index --chmod=+x gradlew` and tell the owner.
- Action versions are pinned to commit SHAs.

---

## 12. Release process

The app (the `enterprise` flavor, the only one) is released as a signed APK on GitHub Releases: `./gradlew :app:assembleEnterpriseRelease`.

1. `main` green on all four checks.
2. Version: `versionCode` always increases by at least 1 (Android refuses updates otherwise); `versionName` follows semantic versioning (`1.0.0`, `1.0.1`, `1.1.0`). The first release is `versionName = "1.0.0"`, `versionCode = 1` (`app/build.gradle.kts`), tag `v1.0.0`. Git tag `vX.Y.Z` is created by the owner.
3. Translations reviewed (`docs/TRANSLATION_REVIEW_*.md`).
4. Owner builds and signs with the real key (never the agent).
5. Release page contains: the APK, its **SHA-256 checksum**, the **signing certificate SHA-256 fingerprint** (same as in the README), release notes, and known limitations (§16).
6. Release notes (`RELEASE_NOTES_X.Y.md`) in plain language: what's new, what's fixed, what can't be blocked, how to update.

The agent can prepare everything except signing and publishing: version bump proposal, release notes, translation review file, changelog, and a checklist of what the owner must do.

---

## 13. Documentation and README standards

Documentation is part of the product. Outdated docs in a security app are a security bug: users make decisions based on them.

### 13.1 README.md must contain, in this order

> **Status (2026-10-23):** all eleven parts exist (Install, First setup, Protection levels and the Credits table were added). The order differs slightly (technical sections sit between them). **Still open:** the signing fingerprint under Security is `<CERT_SHA256_PLACEHOLDER>` until the owner signs the first release; fill it in together with `RELEASE_NOTES_1.0.md`.

1. **What CoreLock is** — one paragraph, no hype.
2. **What it blocks and what it can't** — the four layers in plain words, and the honest limits (§16).
3. **Install** — download from GitHub Releases, how to verify the checksum and signing fingerprint, how to allow installation from unknown sources.
4. **First setup** — VPN permission, choosing a protection level, Private DNS warning, battery optimization, Always-on VPN for the kill switch.
5. **Protection levels** — MINIMAL / STANDARD / STRICT and what each uses.
6. **Permissions** — table: permission → why it's needed.
7. **Network disclosure** — table of every host the app contacts and why (enforced by `OutboundHostsDisclosureTest`).
8. **Privacy** — what's stored, where, encrypted how, how to delete it. No telemetry, ever.
9. **Building from source** — requirements, commands, signing template.
10. **Security** — how to report a vulnerability privately; signing fingerprint.
11. **License and credits** — including blocklist authors (HaGeZi, OISD) and their licenses.

### 13.2 Other documents

- `docs/HOW_IT_WORKS.md` — deeper technical explanation of §5 for curious users and contributors.
- `docs/adr/NNNN-title.md` — **to create** (the folder does not exist yet). One Architecture Decision Record per significant decision (context, decision, alternatives, consequences). Examples worth recording: no Google Play, four-layer design, list choice, QUIC gate limits, removal of Flutter/Rust.
- `RELEASE_NOTES_X.Y.md`, `docs/TRANSLATION_REVIEW_X.Y.md`.
- `SECURITY.md` — vulnerability reporting.

### 13.3 Rules

- A change that alters behaviour, permissions, network hosts, settings or limits **updates the docs in the same change**.
- Write for a non-technical user first; put technical detail in `docs/`.
- Link instead of duplicating.
- When this CLAUDE.md is wrong, propose a correction in the report.

---

## 14. How to work with the owner

The owner (GitHub `NAJJAT`) is Arabic-speaking and often reads reports on his phone, then forwards them to a reviewer. He prefers quality and correctness over speed.

### 14.1 Task protocol

1. **Understand.** Restate the goal in one or two lines if anything is ambiguous. Ask at most three focused questions; otherwise state your assumptions and proceed.
2. **Check state.** `git status`: are there uncommitted changes that would mix with this task? If yes, stop and report.
3. **Plan.** For anything larger than a small fix, list the files you'll change and the risks before writing code.
4. **Implement** in small, reviewable steps.
5. **Verify** (§9.2). Prove fixes fail without the fix.
6. **Report** (§15) and propose commits.

### 14.2 Stop and ask before

- Anything in Section 1.
- Changing the decision order, protection-level defaults, list sources, QUIC limits or any security default.
- Schema changes.
- New permissions, new outbound hosts, new dependencies.
- Deleting files that aren't clearly dead code.
- Any change to the enterprise MITM code.

### 14.3 Push back when

- An instruction would break a rule here, a user-facing promise, or other traffic (calls, VPNs).
- An instruction is based on a wrong diagnosis. Show the evidence.

---

## 15. Report format

Simple, clear English. Short sentences. No filler, no repeating the prompt.

1. **Summary** — two or three lines: what was done and whether it's ready.
2. **What changed** — each file with a one-line reason.
3. **Evidence** — test counts, lint, measurements, `file:line` for important claims. Mark each claim **verified** or **assumed**.
4. **Risks and caveats** — what couldn't be tested (e.g. "Windows only", "not run on a device"), and problems noticed but not fixed.
5. **Proposed commits** — file lists and messages, in order.
6. **Next** — what you're waiting for (a commit, a decision, a device test).

---

## 16. Known limitations and open backlog

### 16.1 Limits by design (state them in the UI and release notes)

- In-app ads in YouTube, Instagram, TikTok and Facebook can't be blocked without root: they come from the same hosts as the content.
- At the DNS layer, Android's resolver makes the request, so per-app Allow rules and ad-SDK exemptions only work at the connection (SNI) layer.
- Encrypted Client Hello (ECH) hides the real server name; only the outer name is visible.
- Apps using hardcoded IPs with no SNI can only be blocked per app.
- The kill switch requires Android's Always-on VPN + lockdown.
- HTTPS inspection reaches few apps: most apps on Android 7+ ignore user-installed CAs or pin certificates, so they stay metadata-only.
- No automatic update check, by design: the app contacts no update server. Settings › About shows the version and opens the GitHub releases page.
- A rooted or compromised device is out of scope.

### 16.2 Open backlog (most important first)

1. Per-app rules at the DNS layer (correlate DNS answers with later connections).
2. Block counters disagree: the notification counter excludes DNS and counts packets; screens use `BlockStats`. SNI/QUIC counters aren't shown; QUIC, limit-full and dual-hop UDP drops aren't recorded. Unify into one source of truth.
3. Compact blocklist storage (64-bit fingerprints in a sorted `LongArray`) to cut STRICT memory from ~44 MB to a few MB.
4. Six ViewModels poll with `while(true)` — 8 loops: AlertInbox, AppDetail, Connections, Dashboard (2), Settings, Statistics (2). Empty `catch (_: Exception) { }` blocks in AlertInbox, Apps, Connections, Rules, Settings, Statistics. Move to Room `Flow` and surface errors. After 1.0.
5. Per-packet allocations on the hot path (`copyOfRange` per UDP packet, IP addresses parsed into Strings per packet in `PrivacyVpnService`); measure first (§6.2). After 1.0.
6. Wi-Fi-only / mobile-data-only rules.
7. Reassemble split ClientHellos on ports other than 443; add DoH lists to the start-up cache (~30 s gap while the encrypted DB loads).
8. Remove the unused master key (`pg_master_key_v1` in `SecureSecretStore`). It only wraps `installation_secret`, which nothing reads; `putString`/`getString` have no callers. Its only other use is `isHardwareBacked()`, which should check `pg_background_key_v1` instead (that key protects the real data, and the master key may not exist on phones without a lock). Also delete the old Keystore entry and the stored secret on upgrade. After 1.0.

When you finish a backlog item, propose removing it here in the same change.

---

## 17. Glossary

| Term | Meaning |
|---|---|
| **TUN** | Virtual network interface created by `VpnService`; we read and write raw IP packets on it. |
| **NXDOMAIN** | DNS answer meaning "this name doesn't exist"; how layer 1 blocks. |
| **SNI** | Server Name Indication: the server name a client sends in plain text at the start of TLS. |
| **ClientHello** | The first TLS message from the client; contains the SNI. |
| **QUIC / HTTP/3** | Web protocol over UDP/443; its first packet (Initial) contains a ClientHello. |
| **DoH / DoT** | DNS over HTTPS / DNS over TLS (port 853): encrypted DNS that can bypass layer 1. |
| **CNAME cloaking** | A tracker hidden behind an alias on the site's own domain; caught by checking the CNAME chain. |
| **BlockSource** | Which mechanism blocked a connection (DNS, SNI, QUIC, DOT, DOH, …). |
| **Protection level** | MINIMAL / STANDARD / STRICT: which lists and gates are active. |
| **Fail closed** | When unsure, block rather than allow. |
| **Lockdown** | Android's "Block connections without VPN"; the real kill switch. |
| **Last-known-good** | The previous working blocklist, kept until a new one is verified. |