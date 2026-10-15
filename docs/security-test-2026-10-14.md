# CoreLock security test, 2026-10-14

Scope: commit 5c977b6, consumer + enterprise. Checklist: OWASP MASVS / MASTG.
The test itself changed nothing in `app/src/main` or `app/src/enterprise`; the fixes came
afterwards, each in its own commit. Status updated 2026-10-15: the Status column gives the
commit for each fix, and §8 has the phone results from that day.

| Status | Findings |
|---|---|
| Fixed | V-01 to V-06, V-08, V-09, V-11, V-13, V-15, V-16 (V-14: OSV scan in CI uncommitted) |
| Partly fixed | V-07, V-10, V-12, V-18 |
| Open | V-17 (by design, documented) |

## Environment and limits

- Release APKs built with a throwaway 30-day key (scratchpad, not in the repo).
- Emulator: **could not run** (no WHPX/AEHD hypervisor on this PC). Device tests ran
  on the Note20 (Android 13 / API 33): consumer release installed fresh as
  `com.privacyguard.app`; tunnel tests used the installed enterprise **debug** build.
- No Rust toolchain installed: cargo-fuzz not run.
- `CLAUDE.md` was not found in the repo or in `~/.claude` at the time; it was added in
  0e81385 (rules for tests on the personal phone).

## 1. Threat model (abuse cases)

| Attacker | Wants | How they would try |
|---|---|---|
| A1 Malicious app on the phone | Reach blocked hosts; stop or blind protection; hide its own traffic | Own resolver or hard-coded IPs; unknown DoH or DoT; broadcasts to CoreLock; spoofed SNI; package name chosen to look like CoreLock; flood alerts or the inspector |
| A2 Spyware that knows CoreLock is installed | Not be detected; learn what CoreLock saw | Look like normal traffic (SNI of a big brand, CDN fronting); stall the detectors; read or dismiss CoreLock notifications (notification listener); screen capture of history screens |
| A3 Attacker on the same Wi-Fi | Read or forge DNS; MITM CoreLock's own connections; poison lists | Redirect 1.1.1.1:443 or 53 to a host with any valid certificate; captive portal; DNS hijack; TLS interception |
| A4 Tampered download server / compromised list | DoS (block everything); unblock things; crash or blow up memory | Add public suffixes or huge files; malformed data; redirects to http |
| A5 Person with the unlocked phone for 5 minutes | Browsing history; turn protection off quietly; install CA or rules | Open app screens; export CSV/PCAP and share; toggle VPN; add Allow rules; adb if enabled |

## 2. Findings

Result: VULNERABLE / OK / NOT TESTED. "Confirmed" = shown by a test or on the device.

| ID | Status | Attacker | What was tried | Result | Sev | How to reproduce | Proposed fix |
|---|---|---|---|---|---|---|---|
| V-01 | **Fixed** 65ebf93 | A3 | DoH upstream with a valid cert for a **different** host | VULNERABLE (confirmed, JVM) | High | `testEnterprise/.../sectest/TlsValidationTest.dohRejectsAValidCertificateForAnotherName`: `postDoh` sends the query and accepts the answer from a server whose cert says `attacker.example`. `DnsHandler.postDoh` uses `SSLSocketFactory.createSocket` without `endpointIdentificationAlgorithm`; raw SSLSocket does no hostname check (also on Android/Conscrypt). HOW_IT_WORKS §3.2 claims "TLS still checks the name". | Set `sslParameters.endpointIdentificationAlgorithm = "HTTPS"` (and SNI) before the handshake, or verify with `HttpsURLConnection.getDefaultHostnameVerifier()`; fail closed (SERVFAIL). Regression test = the test above. |
| V-02 | **Fixed** 65ebf93; confirmed on the phone (§8) | A1, A2 | Plain UDP DNS on a 5-tuple that already has a UDP session | VULNERABLE by code review; **not confirmed on device** (test stopped) | High | `PrivacyVpnService.onUdp`: `dnsHandler.handle` is called only when no session exists. `DnsHandler.handle` returns `false` (packet goes to `UdpForwarder`, which creates a session) for a datagram to port 53 that is a response or has no question. Later datagrams on that flow take the `existing != null` branch and are never checked against the blocklist. | Send every UDP packet to/from port 53 through `DnsHandler` whatever the session state; drop, don't forward, port-53 datagrams it can't treat as a query. Property test: random first datagram + blocked query on the same flow never resolves. |
| V-03 | **Fixed** 65ebf93 (no Android 7–12 device to confirm) | A1 | Any app sends `com.privacyguard.action.STOP_VPN` | VULNERABLE on API 24–32 (code); OK on API 33+ (phone) | High (old Android) | `PrivacyVpnService` line ~438 registers `stopReceiver` with no export flag below Tiramisu, so it is exported. On the API 33 phone the implicit broadcast from adb had no effect. | `ContextCompat.registerReceiver(..., ContextCompat.RECEIVER_NOT_EXPORTED)` on all API levels (the manifest already declares the androidx `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`). |
| V-04 | **Fixed** bdabbac | A1, A2 (enterprise) | Hostile HTTP body against PII redaction | VULNERABLE (confirmed) | Medium | `InspectionDosTest.piiRedactorIsLinearOnHostileBodies`: a 64 KB body of `a.` repeated runs the email regex for over 20 s (benign text: 74 ms). `redactForExport` always runs, on the **single** capture worker, so one request stalls inspection and the 4096-item queue then drops captures for all apps. | Bound the work: possessive quantifiers / atomic groups or a hand-written scanner for emails; cap the text passed to the regexes (e.g. 64 KB) and run with a time budget; mark the payload "redaction skipped". |
| V-05 | **Fixed** bdabbac | A1 (app label, SNI) → A5 opening the CSV | CSV formula injection | VULNERABLE (confirmed) | Medium | `test/.../sectest/ExportInjectionTest`: `=`, `+`, `-`, `@` and tab-prefixed values are exported as formulas (`StatisticsViewModel.csvEscape` only doubles quotes). | Prefix cells that start with `= + - @ \t \r` with `'`, then quote. |
| V-06 | **Fixed** f978f47 | A1, A2 | Arbitrary SNI (bidi U+202E, HTML, formula, other brand) | VULNERABLE (confirmed parser; UI rendering not checked visually) | Medium | `UntrustedInputFuzzTest.sniIsNotValidatedAsAHostname`: `ClientHelloParser` returns the string verbatim. SNI is preferred over the DNS name for the recorded host, so an app can record its C2 connection as `www.google.com`. Same text reaches Compose screens, PDF report and notifications. | Accept SNI only if it is a valid LDH hostname (reuse `CertForger.isValidHostname`); otherwise record "invalid SNI" as a finding. Flag SNI ≠ DNS name for that IP. Strip bidi controls from every host and app label shown or exported. |
| V-07 | **Fixed** f978f47 for TLS and QUIC SNI. Open: connections without a readable SNI (raw IP, ECH) | A1, A2 | Reach a blocklisted host without DNS (hard-coded IP, own resolver, unknown DoH by IP, DoH over HTTP/3) | VULNERABLE (code; partly documented in §9) | Medium | `TcpForwarder.classifyFirstData` reads the SNI but never checks it against `DomainFilter`/`securityFilter`. UDP 443 (QUIC) is forwarded with no name check. | Check SNI (TCP 443 and other TLS ports) against the same lists and reset on a hit; consider QUIC Initial SNI parsing, or a "block QUIC" option. |
| V-08 | **Fixed** bdabbac, opt-out switch c1ef12b | A2, A5 | Screenshots / Recents / screen recording of history screens | VULNERABLE (confirmed, release) | Medium | `adb shell dumpsys window`: release MainActivity `fl=0x81810100` has no FLAG_SECURE (0x2000). `BaseActivity` sets it only when `AppSecurityMonitor` reports risk. | FLAG_SECURE always on screens with history/payloads (or app-wide), with an opt-out setting. |
| V-09 | **Fixed** bdabbac, c1ef12b | A5, A2 | Lock-screen and notification-listener exposure | VULNERABLE (code) | Low–Med | Alerts (`NotificationHelper` 170, 268, 339, 368, 396) put app + domain in the text; no `setVisibility(VISIBILITY_PRIVATE)` or `setPublicVersion`. | `VISIBILITY_PRIVATE` + a public version ("PrivacyGuard alert"); keep the inbox as the source of truth (a notification listener can dismiss them). |
| V-10 | **Partly fixed** a7f37eb. Open: no hash or signature check, no limit on how much one update may change | A4 | Download integrity and safe failure | PARTLY OK | Medium | OK: HTTPS only, redirects off, 32 MB / 8 MB caps, failure keeps the old list. Not OK: no hash or signature; `UntrustedInputFuzzTest`: a list can add `co.uk` / `com.au` / `googleapis.com` and a Cyrillic homoglyph; a 32 MB list is ~414k names, ~240 MB heap. | Refuse public suffixes and non-ASCII (require punycode); cap entries per source; reject an update that changes more than X % at once; pin hosts or check a published hash where the source offers one. |
| V-11 | **Fixed** a7f37eb | A1 (enterprise) | App named `com.privacyguard.appX` | VULNERABLE (code) | Low | `MitmTlsInterception.isPrivacyGuardTraffic` uses `startsWith("com.privacyguard.app")`, so such an app is intercepted but its payloads are never logged or shipped. | Compare with `BuildConfig.APPLICATION_ID` exactly (or check UID). |
| V-12 | **Partly fixed** a7f37eb. Open: the first 50 domains still fill the watch list | A3/A4 | crt.sh client | Low | Low | `CtMonitor`: unbounded `body.string()`; OkHttp follows redirects by default; apex = last two labels (`co.uk`); first 50 domains fill the watch list, so an app can crowd it. Deep JSON nesting gave JSONException (fine). | `readUtf8Capped`, `followRedirects(false)`, public-suffix-aware apex, rotate the watch list. |
| V-13 | **Fixed** a7f37eb | A5 (bug report), A2 | Release logging | PARTLY OK | Low | `Log.d/v/i` stripped (verified in dex). `Log.w/e` still print `SessionKey` (IPs/ports) and SSL exception messages that can contain hostnames (`MitmEngine` 161, 413; `TcpForwarder`; `UdpForwarder`). | Drop keys/messages from warnings, or log only a counter. |
| V-14 | **Fixed** a7f37eb (guava, dependency verification), cab6edc (key files ignored); OSV scan in CI uncommitted: release APK libraries (158, none affected) and `rust/Cargo.lock`, where `windows` 0.17.2 (desktop tool only) is excused until 2027-04-15 in `rust/osv-scanner.toml` | A4 supply chain | CVEs and build integrity | Low | Low | OSV: `guava 31.0.1-jre` (GHSA-5mg8-w23w-74h3, GHSA-7g45-4rm6-3mm3; temp-dir issues, low on Android); `windows 0.17.2` (RUSTSEC-2022-0008) only in the desktop tool. No `gradle/verification-metadata.xml`. `.gitignore` does not exclude `*.jks`/`*.keystore`. | Force guava ≥ 33 (or drop coroutines-guava if unused); add Gradle dependency verification; ignore key files globally; add OSV scan to CI. |
| V-15 | **Fixed** a7f37eb | A5 / reverse engineer | `META-INF/version-control-info.textproto` in release | Info | Info | Release APK carries the git commit hash. | `android.vcsInfo { include = false }` if not wanted. |
| V-16 | **Fixed** f978f47 | A1 | CNAME cloaking / second question | VULNERABLE (code) | Low | Answers are not checked for CNAMEs to blocked names; only the first question is checked (existing `DnsBlockingPropertyTest.a_second_question_is_not_checked`). | Check CNAME targets in `injectDnsResponse`; reject QDCOUNT ≠ 1. |
| V-17 | **Open**, by design (documented) | A1, A2 | Subdomain of a user Block rule | BY DESIGN, documented (confirmed, phone) | Low (usability) | The rule dialog says "Matches this exact name. Write *.example.com…" and HOW_IT_WORKS §4.2 says the same; still easy to miss. Custom rule "Block `example.net`" (`AdsViewModel.addCustomRule` → `FilterRule.blockDomain`) stores the bare name; `FilterRule.domainMatches` only matches subdomains for `*.` patterns. On the Note20, `example.net` failed to resolve but `www.example.net` and `x.203.0.113.7.nip.io` (rule `203.0.113.7.nip.io`) resolved. A user blocking `tiktok.com` still lets `www.`/`api.tiktok.com` through. Blocklists are not affected (`BlocklistRepo` adds `x` and `*.x`). | Store a user Block rule as both `name` and `*.name` (or match `name` and its subdomains), as blocklists do; regression test on `FilterRule.matches`. |

## 3. Checks that passed (OK)

| Area | Result |
|---|---|
| Release build (both flavors) | `debuggable` absent/false; `run-as` refused; no `DebugHooksReceiver`, `privacyguard.debug`, testapp or MITM debug strings in dex; consumer dex has no MITM code; release refuses to build without a release key (CI checks it). |
| Exported components (adb on phone) | MainActivity: unknown `nav_route` ignored, no crash. BootReceiver: spoofed BOOT_COMPLETED → SecurityException. VpnService, NotificationDismissReceiver, FileProvider, startup provider: not exported (denied). Tile / JobService: system permission. WorkManager diagnostics and profileinstaller: DUMP only. No deep links. Enterprise adds DeviceAdminReceiver (BIND_DEVICE_ADMIN). |
| MITM upstream validation | Expired, self-signed and wrong host → SSLHandshakeException; valid → accepted (`TlsValidationTest`, same SSL setup as `MitmEngine`; default trust = system CAs only, since there is no network security config). |
| MITM CA and listener | Key in AndroidKeyStore (not exportable), pathLen 0, 1 year, regenerated when out of policy; leaf exact SAN, 30 days; SNI validated before forging; listener on loopback, accepts only the expected source port; consent checked in `shouldIntercept`; pinned apps and failed hosts pass through. |
| Data at rest | SQLCipher, passphrase wrapped by AES-GCM Keystore key; backups and device transfer excluded; no clipboard use; FileProvider limited to `exports/`, `certs/`. |
| Fuzzing, no crashes or hangs | Random mutation (fixed seeds): ClientHello/JA3/JA4/EncryptionEnforcer 20k, crt.sh 20k, 3 blocklist formats 20k, start-up cache 5k, PayloadParser 10k. Jazzer 0.30.0, 3 min each: packets+DNS 21.8M runs (cov 331), TLS 4.2M (cov 378), HTTP+payload+PII 267k (cov 375; inputs ≤ 1.2 KB, too small to hit V-04), crt.sh 58.7M. |
| CI | Actions pinned to SHAs, `contents: read`, gitleaks, wrapper checksum. No secrets in 112 commits (only `change-me` placeholders). |

## 4. Not tested

| Item | Why |
|---|---|
| Leaks during user stop, captive portal, and anything under Always-on + lockdown | Tested later (§6) except these. A user stop leaves no protection by design. |
| Runtime egress capture vs the onboarding list (A3 in onboarding) | Needs a capture setup; only the static `OutboundHostsDisclosureTest` exists. |
| DoT, IPv6, QUIC on the device; domain fronting; alert flooding at runtime | Code review only (V-07, DnsBypassGuard drops 853 and listed DoH IPs). |
| ~~V-02 on the device~~ | Done on 2026-10-15 (§8): fixed. |
| ~~cargo-fuzz on `rust/privacyguard-core`~~ | Done later (§6). Note: the Android APK currently ships no Rust `.so`. |
| Enterprise release on the device | Same package id as the installed debug build with another key; installing it would mean uninstalling (data loss). |

## 5. Test files added (committed in deb8459)

- `app/src/test/java/com/privacyguard/sectest/UntrustedInputFuzzTest.kt`
- `app/src/test/java/com/privacyguard/sectest/ExportInjectionTest.kt` (V-05)
- `app/src/testEnterprise/java/com/privacyguard/sectest/TlsValidationTest.kt` (V-01)
- `app/src/testEnterprise/java/com/privacyguard/sectest/InspectionDosTest.kt` (V-04)
- `app/src/testEnterprise/java/com/privacyguard/sectest/JazzerTargets.kt`

The V-01, V-04 and V-05 tests failed when they were written. They have passed since the
fixes (65ebf93, bdabbac), and `UntrustedInputFuzzTest` now asserts the V-06 and V-10
fixes. Each fix commit also adds its own regression tests (listed in the commit message).

## 6. Follow-up, same day (after the fixes)

### cargo-fuzz (WSL Ubuntu 26.04, nightly 2026-10-13, cargo-fuzz 0.13.2, ASan)

| Target | Time | Runs | Coverage | Crashes |
|---|---|---|---|---|
| `classify_packet` (+ `pg_shannon_entropy`) | 4 workers × 30 min | ~1.6 billion | cov 49 | 0 |
| `ja3` | 4 workers × 30 min | ~76 million | cov 247 | 0 |

Seeded with an IPv4 TLS/DNS/HTTP/QUIC packet each and two ClientHellos. The fuzz crate
did not build at first: four JNI exports in `src/lib.rs` were not behind
`#[cfg(feature = "android")]` (the attribute covered only the first one), so any
`--no-default-features` build failed. Fixed in 0ca1e8b.

### VPN leak test (Note20, enterprise debug at c1ef12b, Private DNS "opportunistic", no Always-on)

Method: a shell loop resolves a unique name under a blocked canary (`*.10.0.0.1.nip.io`)
about 4 times a second; a name that resolves got past the filter. Control: an unblocked
`nip.io` name resolves. Rules and logs removed afterwards.

| Scenario | Probes | Leaked | Note |
|---|---|---|---|
| Steady state | ~130 | 0 | |
| Airplane mode 8 s, then 25 s after | ~300 | 0 | Tunnel stays up across the change |
| Wi-Fi off (mobile data) 15 s, Wi-Fi back 25 s | ~340 | 0 | Same |
| App process crash (`am crash`), run 1 | 21 in window | 21 | No restart scheduled by Android; tunnel down until a manual start at +26 s |
| App process crash, run 2 | 64 in window | 3 | Android restarted the service after 1 s; leaks +3.4 s to +5.9 s |

Same test with **Always-on + "Block connections without VPN"** on (set through
Settings, restored to off afterwards). Each probe also looked up an unblocked control
name:

| Scenario | Probes | Leaked | Control failed | Note |
|---|---|---|---|---|
| Steady state | 11 | 0 | 0 | |
| Crash, run 1 | 64 | 0 | 64 | No restart by Android; manual start after ~20 s |
| Crash, run 2 | 26 | 0 | 15 | Restarted after 1 s ("crashed quickly"), tunnel back at +6 s |
| Crash, run 3, no manual start | — | — | — | **Not recovered after 120 s**; phone had no internet until the VPN was started again |

Conclusion: no leaks while the tunnel exists. Without lockdown, blocked names resolve
directly while it is down after a crash. With lockdown nothing leaks, but nothing
else works either: Android scheduled a service restart only when the process had
crashed shortly before ("crashed quickly"); after an ordinary crash it logged
`am_proc_died` and no restart, and Always-on did not bring the VPN back either
(Samsung, Android 13).

| ID | Status | Attacker | What was tried | Result | Sev | How to reproduce | Proposed fix |
|---|---|---|---|---|---|---|---|
| V-18 | **Partly fixed** 45bce36, cab6edc, 5a0f8a4 (watcher process, not yet tested on the phone; §8) | Crash (bug or A1 crafted input) | Recovery after the VPN process dies | VULNERABLE (confirmed, phone) | Medium (availability) | `adb shell am crash com.privacyguard.app.enterprise`: tunnel gone, no `Scheduling restart` in the log, not back after 120 s. Without lockdown: unfiltered until the user reopens the app. With lockdown: no internet until then. | A watchdog outside the process that restarts the service when `BootReceiver` says protection should be on but the tunnel is down (e.g. a periodic JobScheduler job or an `AlarmManager` alarm set while running); a "Protection stopped" notification with a Restart action; keep the native/packet paths panic- and exception-safe. Re-test with this scenario. |

Emulator: on the API 37 image the console packet capture saw nothing; a logging DNS
forwarder (`-dns-server`) worked. The emulator subdomain probe was invalid (V-17), so no
emulator results are reported.

## 7. Feature checks on the phone (enterprise debug; the fixes found here are in 0ca1e8b)

| Feature | Check | Result |
|---|---|---|
| TLS fingerprint baseline | Test app, BouncyCastle client offering N fewer suites (`--es probe tls_bc --ei mb N`) gives a JA4 never sent before | OK. After learning: new JA4 → **Alert**, repeat → nothing. Right after an update (versionCode 3 → 4): new JA4 → **Learned** silently. After the window: new JA4s → one **Alert** each. |
| Ad-SDK blocking | Test app resolves ad and login hosts (`probe ad_hosts`) | OK. `an.facebook.com`, `app.adjust.com`, `inapps.appsflyersdk.com`, `app-measurement.com` blocked; `graph.facebook.com`, `m.facebook.com`, `firebaseinstallations.googleapis.com` resolve (Facebook login keeps working). |
| Mic timeline | Test app records 5 s (`probe mic`, RECORD_AUDIO granted for the test and revoked after) | Recorded (23:08, screen on, "Unknown app": no Usage Access). **Bug, fixed:** the "Did any app use my mic?" search reported 3 uses instead of 1: two rows from 13 Oct whose process had died stayed open ("in use now") and matched every window. Watching now closes such rows as "end not recorded" when it starts, and a search counts them only if they started inside the window. Verified on the phone after the fix. |
| Location audit | Compared with `dumpsys package` | OK: the two user apps with background location are the ones shown as "Always". |
| Reduce ad tracking checklist | Each "Open" | OK: Google Ads settings, Meta Accounts Center and My Ad Center (browser), Google Maps (Timeline lives there). |
| Purpose groups, domain detail | Facebook → every connection → By purpose; `graph.facebook.com` | OK: groups with explanations, owner, purpose, tracker entry, 7-day frequency, data, first/last seen, apps, and "not what is inside it". |

Other findings:
- **Settings text was wrong** (`set_always_on_hint`): it said Android restarts PrivacyGuard after a crash with Always-on; V-18 shows it does not. Corrected in all four languages (0ca1e8b), and again for automatic restart (5a0f8a4).
- **Bottom navigation and accessibility:** `uiautomator dump` showed the tab labels with zero-size bounds and no click action. A Compose semantics test (`BottomBarAccessibilityTest`, uncommitted) shows each tab is one node with a size, role Tab, its selected state and a click action, with the label read once. So the dump most likely showed the merged child nodes, not the tabs. One real bug: the Settings badge count was never read (Material clears the icon's semantics), so the tab now says "3 threat alerts". **Still open:** checking with TalkBack on the phone.
- **Shell UID (2000) was labelled "Removed app"** in alerts: `getNameForUid` gives the shared-user name `android.uid.shell:2000`, which no label lookup knows. UID 2000 now maps to `com.android.shell`, labelled "ADB / shell", and older rows stored under the shared-user name get the same label (uncommitted, `ShellUidLabelTest`).
- **Apps list:** Chrome showed HIGH risk next to "0 destinations · 0 B": the risk came from all history, the counts from 24 h. Risk and the suspicious count now use the same 24 h, and the subtitle says "Risk and traffic from the last 24 h". The stalkerware check still looks at all behaviour (uncommitted, `AppsRiskWindowTest`).
- Test traffic left items in the alert inbox (test-app TLS changes, nip.io subdomain alerts). Not yet removed: that needs the phone (debug hook `DELETE_TEST_ALERTS`, dry run first). The test app is now at versionCode 5 (going back would need an uninstall).

## 8. Re-test on the phone, 2026-10-15

Note20, Android 13, enterprise debug. Same probe as §6: a unique name under the blocked
canary `*.10.0.0.1.nip.io` about 4 times a second, plus an unblocked control name, and a
check for a `tun*` interface. Every setting was put back afterwards (Always-on off,
lockdown off, Private DNS opportunistic, Power saving on) and the canary rule was removed.

### V-02: fixed

New test-app probe `dns_same_flow` (`NetProbes.java`, cab6edc). It first sends a datagram
to port 53 that is not a query, which used to open a UDP session, then blocked queries on
the same 5-tuple. Result: 8 of 8 blocked queries got NXDOMAIN, and the control resolved.

### V-18: crash recovery

Build 45bce36 (watchdog with a 30 s check alarm):
- With Power saving on, Android postponed the check alarm (`battery_saver=+364d` in
  `dumpsys alarm`), so nothing recovered.
- When Android itself restarted the service, the tunnel came back at +5.4 s, because
  blocklists loaded from cache (3.5 s) before the tunnel was built.

Build cab6edc. Changes: the tunnel opens before filters load and blocks until they are
ready; an exact allow-while-idle watchdog restarts protection, at most 3 times in 30 min;
the lockdown state is read live. `am crash` each time, at least 3 minutes apart:

| Power saving | Lockdown | Android restarted the service | Android did not restart it |
|---|---|---|---|
| On | Off | Tunnel back at +2.3 s, 7 probes leaked | Watchdog restart at +25.7 s, tunnel back at +28 s; unfiltered until then |
| Off | Off | Tunnel back at +2.3 s, 7 probes leaked | Tunnel back at +29.5 s (+47.6 s before the restart broadcast was sent as a foreground broadcast) |
| Off | On | — | Tunnel back at +22.7 s, from the log (the probe failed on this run); no internet until then, no leaks |
| On | On | — | Tunnel back at +25 s; no internet until then |

- After the 3 restarts the budget allows, "Protection stopped" was posted, as designed.
- After several crashes in a row, Android logs "crashed too many times, killing! Reason:
  crashed quickly". It then refuses background starts until the user opens the app. No
  app can work around this.
- "Close app" (swiping it away in Recents) leaves protection running. Force stop ends it.
  This is Android's doing: a force stop also removes the app's alarms, so nothing can
  recover from it.
- The tunnel rebuilt after a worker failure is `tun1`. The app does not assume `tun0`.

Windows that remain, all from Android:
- **DNS routing lag after `establish()`:** 1–2 probes resolve directly at the moment the
  new `tun` appears.
- **VPN switch under lockdown:** once, a single probe resolved at the instant the new
  tunnel appeared. Android briefly lets traffic past lockdown while it switches VPNs.

Not yet on the phone (5a0f8a4, unit-tested only): a small watcher service in its own
`:watchdog` process. It restarts protection about 1 s after the VPN process dies and
replaces the 30 s check alarm. A non-wakeup backup alarm covers both processes dying.
There is a Settings switch "Automatic restart after a crash", which asks for Alarms &
reminders access. The full crash matrix and an overnight battery measurement still have
to be run with it.

### Always-on turned off in Android settings

When Always-on is turned off, Android revokes the VPN (`onRevoke`), and protection stops.
PrivacyGuard then posted "Android is blocking all traffic…", which was false: lockdown was
off. That text now appears only while lockdown is really on (cab6edc reads it live). Since
5a0f8a4, a revoke is treated as a stop: no drop alert, no restart, and the
notice "Always-on VPN was turned off in Android settings, so Android stopped PrivacyGuard.
Tap to turn protection back on." It is not yet confirmed on the phone that Android already
reports Always-on as off when `onRevoke` runs.
