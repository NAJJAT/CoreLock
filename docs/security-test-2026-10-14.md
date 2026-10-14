# CoreLock security test, 2026-10-14

Scope: commit 5c977b6, consumer + enterprise. Checklist: OWASP MASVS / MASTG.
Report only: nothing in `app/src/main` or `app/src/enterprise` was changed. Not committed.

## Environment and limits

- Release APKs built with a throwaway 30-day key (scratchpad, not in the repo).
- Emulator: **could not run** (no WHPX/AEHD hypervisor on this PC). Device tests ran
  on the Note20 (Android 13 / API 33): consumer release installed fresh as
  `com.privacyguard.app`; tunnel tests used the installed enterprise **debug** build.
- No Rust toolchain installed: cargo-fuzz not run.
- `CLAUDE.md` was not found in the repo or in `~/.claude`.

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

| ID | Attacker | What was tried | Result | Sev | How to reproduce | Proposed fix |
|---|---|---|---|---|---|---|
| V-01 | A3 | DoH upstream with a valid cert for a **different** host | VULNERABLE (confirmed, JVM) | High | `testEnterprise/.../sectest/TlsValidationTest.dohRejectsAValidCertificateForAnotherName`: `postDoh` sends the query and accepts the answer from a server whose cert says `attacker.example`. `DnsHandler.postDoh` uses `SSLSocketFactory.createSocket` without `endpointIdentificationAlgorithm`; raw SSLSocket does no hostname check (also on Android/Conscrypt). HOW_IT_WORKS §3.2 claims "TLS still checks the name". | Set `sslParameters.endpointIdentificationAlgorithm = "HTTPS"` (and SNI) before the handshake, or verify with `HttpsURLConnection.getDefaultHostnameVerifier()`; fail closed (SERVFAIL). Regression test = the test above. |
| V-02 | A1, A2 | Plain UDP DNS on a 5-tuple that already has a UDP session | VULNERABLE by code review; **not confirmed on device** (test stopped) | High | `PrivacyVpnService.onUdp`: `dnsHandler.handle` is called only when no session exists. `DnsHandler.handle` returns `false` (packet goes to `UdpForwarder`, which creates a session) for a datagram to port 53 that is a response or has no question. Later datagrams on that flow take the `existing != null` branch and are never checked against the blocklist. | Send every UDP packet to/from port 53 through `DnsHandler` whatever the session state; drop, don't forward, port-53 datagrams it can't treat as a query. Property test: random first datagram + blocked query on the same flow never resolves. |
| V-03 | A1 | Any app sends `com.privacyguard.action.STOP_VPN` | VULNERABLE on API 24–32 (code); OK on API 33+ (phone) | High (old Android) | `PrivacyVpnService` line ~438 registers `stopReceiver` with no export flag below Tiramisu, so it is exported. On the API 33 phone the implicit broadcast from adb had no effect. | `ContextCompat.registerReceiver(..., ContextCompat.RECEIVER_NOT_EXPORTED)` on all API levels (the manifest already declares the androidx `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`). |
| V-04 | A1, A2 (enterprise) | Hostile HTTP body against PII redaction | VULNERABLE (confirmed) | Medium | `InspectionDosTest.piiRedactorIsLinearOnHostileBodies`: a 64 KB body of `a.` repeated runs the email regex for over 20 s (benign text: 74 ms). `redactForExport` always runs, on the **single** capture worker, so one request stalls inspection and the 4096-item queue then drops captures for all apps. | Bound the work: possessive quantifiers / atomic groups or a hand-written scanner for emails; cap the text passed to the regexes (e.g. 64 KB) and run with a time budget; mark the payload "redaction skipped". |
| V-05 | A1 (app label, SNI) → A5 opening the CSV | CSV formula injection | VULNERABLE (confirmed) | Medium | `test/.../sectest/ExportInjectionTest`: `=`, `+`, `-`, `@` and tab-prefixed values are exported as formulas (`StatisticsViewModel.csvEscape` only doubles quotes). | Prefix cells that start with `= + - @ \t \r` with `'`, then quote. |
| V-06 | A1, A2 | Arbitrary SNI (bidi U+202E, HTML, formula, other brand) | VULNERABLE (confirmed parser; UI rendering not checked visually) | Medium | `UntrustedInputFuzzTest.sniIsNotValidatedAsAHostname`: `ClientHelloParser` returns the string verbatim. SNI is preferred over the DNS name for the recorded host, so an app can record its C2 connection as `www.google.com`. Same text reaches Compose screens, PDF report and notifications. | Accept SNI only if it is a valid LDH hostname (reuse `CertForger.isValidHostname`); otherwise record "invalid SNI" as a finding. Flag SNI ≠ DNS name for that IP. Strip bidi controls from every host and app label shown or exported. |
| V-07 | A1, A2 | Reach a blocklisted host without DNS (hard-coded IP, own resolver, unknown DoH by IP, DoH over HTTP/3) | VULNERABLE (code; partly documented in §9) | Medium | `TcpForwarder.classifyFirstData` reads the SNI but never checks it against `DomainFilter`/`securityFilter`. UDP 443 (QUIC) is forwarded with no name check. | Check SNI (TCP 443 and other TLS ports) against the same lists and reset on a hit; consider QUIC Initial SNI parsing, or a "block QUIC" option. |
| V-08 | A2, A5 | Screenshots / Recents / screen recording of history screens | VULNERABLE (confirmed, release) | Medium | `adb shell dumpsys window`: release MainActivity `fl=0x81810100` has no FLAG_SECURE (0x2000). `BaseActivity` sets it only when `AppSecurityMonitor` reports risk. | FLAG_SECURE always on screens with history/payloads (or app-wide), with an opt-out setting. |
| V-09 | A5, A2 | Lock-screen and notification-listener exposure | VULNERABLE (code) | Low–Med | Alerts (`NotificationHelper` 170, 268, 339, 368, 396) put app + domain in the text; no `setVisibility(VISIBILITY_PRIVATE)` or `setPublicVersion`. | `VISIBILITY_PRIVATE` + a public version ("PrivacyGuard alert"); keep the inbox as the source of truth (a notification listener can dismiss them). |
| V-10 | A4 | Download integrity and safe failure | PARTLY OK | Medium | OK: HTTPS only, redirects off, 32 MB / 8 MB caps, failure keeps the old list. Not OK: no hash or signature; `UntrustedInputFuzzTest`: a list can add `co.uk` / `com.au` / `googleapis.com` and a Cyrillic homoglyph; a 32 MB list is ~414k names, ~240 MB heap. | Refuse public suffixes and non-ASCII (require punycode); cap entries per source; reject an update that changes more than X % at once; pin hosts or check a published hash where the source offers one. |
| V-11 | A1 (enterprise) | App named `com.privacyguard.appX` | VULNERABLE (code) | Low | `MitmTlsInterception.isPrivacyGuardTraffic` uses `startsWith("com.privacyguard.app")`, so such an app is intercepted but its payloads are never logged or shipped. | Compare with `BuildConfig.APPLICATION_ID` exactly (or check UID). |
| V-12 | A3/A4 | crt.sh client | Low | Low | `CtMonitor`: unbounded `body.string()`; OkHttp follows redirects by default; apex = last two labels (`co.uk`); first 50 domains fill the watch list, so an app can crowd it. Deep JSON nesting gave JSONException (fine). | `readUtf8Capped`, `followRedirects(false)`, public-suffix-aware apex, rotate the watch list. |
| V-13 | A5 (bug report), A2 | Release logging | PARTLY OK | Low | `Log.d/v/i` stripped (verified in dex). `Log.w/e` still print `SessionKey` (IPs/ports) and SSL exception messages that can contain hostnames (`MitmEngine` 161, 413; `TcpForwarder`; `UdpForwarder`). | Drop keys/messages from warnings, or log only a counter. |
| V-14 | A4 supply chain | CVEs and build integrity | Low | Low | OSV: `guava 31.0.1-jre` (GHSA-5mg8-w23w-74h3, GHSA-7g45-4rm6-3mm3; temp-dir issues, low on Android); `windows 0.17.2` (RUSTSEC-2022-0008) only in the desktop tool. No `gradle/verification-metadata.xml`. `.gitignore` does not exclude `*.jks`/`*.keystore`. | Force guava ≥ 33 (or drop coroutines-guava if unused); add Gradle dependency verification; ignore key files globally; add OSV scan to CI. |
| V-15 | A5 / reverse engineer | `META-INF/version-control-info.textproto` in release | Info | Info | Release APK carries the git commit hash. | `android.vcsInfo { include = false }` if not wanted. |
| V-16 | A1 | CNAME cloaking / second question | VULNERABLE (code) | Low | Answers are not checked for CNAMEs to blocked names; only the first question is checked (existing `DnsBlockingPropertyTest.a_second_question_is_not_checked`). | Check CNAME targets in `injectDnsResponse`; reject QDCOUNT ≠ 1. |

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
| Leaks during VPN start/stop/crash, Wi-Fi↔mobile, airplane mode, captive portal | No emulator (hypervisor missing). Design note: outside Always-on + lockdown, Android sends traffic directly whenever the tunnel is down; that is platform behaviour. |
| Runtime egress capture vs the onboarding list (A3 in onboarding) | Needs a capture setup; only the static `OutboundHostsDisclosureTest` exists. |
| DoT, IPv6, QUIC on the device; domain fronting; alert flooding at runtime | Code review only (V-07, DnsBypassGuard drops 853 and listed DoH IPs). |
| V-02 on the device | Stopped before a conclusive result. |
| cargo-fuzz on `rust/privacyguard-core` | No Rust toolchain. Note: the Android APK currently ships no Rust `.so`. |
| Enterprise release on the device | Same package id as the installed debug build with another key; installing it would mean uninstalling (data loss). |

## 5. Test files added (uncommitted)

- `app/src/test/java/com/privacyguard/sectest/UntrustedInputFuzzTest.kt`
- `app/src/test/java/com/privacyguard/sectest/ExportInjectionTest.kt` (fails: V-05)
- `app/src/testEnterprise/java/com/privacyguard/sectest/TlsValidationTest.kt` (fails: V-01)
- `app/src/testEnterprise/java/com/privacyguard/sectest/InspectionDosTest.kt` (fails: V-04)
- `app/src/testEnterprise/java/com/privacyguard/sectest/JazzerTargets.kt`

The failing tests are the regression tests for V-01, V-04 and V-05; they pass once fixed.
