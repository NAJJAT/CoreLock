# Bug-fix progress (branch bug-fixes)

Working log for the 1.2.x / 1.3 bug-fix round. Security details stay out of this file
until they are fixed; entries use generic wording.

## Part 1: audit (read-only)

- 2026-11-11: started. Branch `bug-fixes`, clean tree at `a0ab4a1`.
- 2026-11-11: audit done (code reading). Areas covered: packet path and parsers, blocking
  logic, resource limits, app attribution, HTTPS inspection, SIEM, secrets and crypto,
  components, data handling, blocklist pipeline, lifecycle, database, build and CI, docs.
- Findings: 18 items (F-1 to F-18), reported in chat to the owner. Details stay in chat
  until each item is fixed.
- Status: done; owner's decisions received 2026-11-11.

## Part 2: fixes

Owner said go on 2026-11-11. Baseline: 1,202 unit tests, 0 failures. Hot path on the JVM: SNI
check 3.39 µs per connection, QUIC gate 9.56 µs per new flow (new QuicGateCostTest).
Device checks run on the owner's phone from 2026-11-11 (owner's decision); the emulator was
used before that.

- F-1 IPv4 fragments: already dropped by the TUN reader. Added a regression test and a
  Diagnostics count. Mutation proof: the test fails with the check removed.
- F-2 connection limits: 4,096 TCP, 4,096 UDP, 1,024 per app; idle UDP closed first, then
  refused and counted. Normal browsing peak measured on the emulator: about 120 sessions.
- F-3 own-app check: exact application ID only; a source scan test keeps it so.
- F-7 TLS version: read from the handshake, not the record header.
- F-10 TLS name bounds: the server name must lie inside its extension, and each extension
  inside the extension block. New fuzz target (ParserBoundsFuzzTest); the fuzzer found the
  second case. Mutation proof done.
- F-11 DNS name limits: label and name lengths as RFC 1035 allows; names inside a record end
  inside that record. New tests and fuzz target. Mutation proof done.
- F-6 QUIC reassembly: handshake data offered twice with different bytes makes the flow
  unreadable (fail closed). The QUIC fuzz mix keeps most frames honest so flows still reach
  the deep states (coverage floor unchanged). Mutation proof: 2 tests fail without the fix.
  QUIC gate 9.47-9.58 µs, no regression.
- Checkpoint after F-6: 1,236 unit tests, 0 failures; lint passed (warnings only); R8 passed.
  Next: F-12, then F-4, F-5, F-8, F-9, F-13, F-14, F-15, F-16, the known items, and the
  smaller items. SNI cost not yet re-measured after F-10.

### 2026-11-13 (new session, resumed from the log)

- F-12 empty catches: list downloads count each failed attempt (network, HTTP status, size)
  and no longer swallow cancellation; owner lookups count read errors; the inspection relay
  closes both sides on its own (a failing first close left the second open). New
  Diagnostics row "Handled errors". Tests failed on the old code (4 of 6), pass now.
- F-4 inspection limit: at most 32 connections decrypted at once, on the engine's own
  bounded thread pool (no longer the shared IO pool). Above the limit a connection goes to
  its server undecrypted (its name was already checked), counted in Diagnostics. Every way
  an interception ends gives its slot back. Mutation proof: 2 tests fail without the fix.
- F-5 SIEM queue: sending re-checks inspection, consent and the shipping switch, and drops
  the queue when any is off; events go only to the endpoint they were queued for; queue
  bounded at 8 MB as well as 1,000 events; failed uploads back off from 30 s, doubling to
  30 min ("Ship now" still tries at once). Mutation proof: 5 of 7 tests fail without it.
- Checkpoint: 1,256 unit tests, 0 failures; lint passed; R8 passed.
- F-8 site names in cleartext web requests: the site name of an unencrypted request is now
  checked through the same name decision as DNS and TLS names (new source "HTTP"). A request
  whose site name can't be read is reset at Standard and Strict (fail closed). Bounded reader
  (8 KB of headers), new fuzz target (HttpHostFuzzTest). Mutation proof: 4 tests fail.
- F-9 re-check by name: when rules or lists change, open connections are checked again by
  their TLS, QUIC or HTTP site name, not only by address and app. Unit test for the decision;
  the service wiring is checked by code reading.
- F-13 TLS fingerprint baseline: rows not seen for 180 days are deleted, at most 50 per app
  and 20,000 in total. Mutation proof: 3 tests fail.
- F-14 Block rules cover subdomains (ADR 0017, Proposed); Allow rules stay exact. Property
  test model updated, seed kept. Rule hint text changed in 4 languages.
- F-15 address rules are IPv4 only: documented as a known limit (README, BLOCKING_POLICY,
  HOW_IT_WORKS, CLAUDE.md).
- F-16 connections whose app can't be identified: still allowed, now counted in Diagnostics.
  Code reading only.
- Known item 16: a CA rotation that can't move Android's trust shows the Home card again.
  Two new tests.
- Smaller items: unused status text field removed (the screen uses translated texts);
  certificate serial numbers made positive and full-width in one helper (CertSerialTest);
  docs for fragmented UDP and the time before the first unlock.
- Checkpoint: 1,288 unit tests, 0 failures; lint 0 errors; R8 passed.
- Hot path: SNI check 3.57-4.07 µs over five runs today (machine slower than on 11-11; an
  A/B run of the old and new rule matching gave 3.67 against 3.66 µs, so no regression from
  the code). QUIC gate 10.0-10.4 µs; its code did not change today.
- Not done: V-18 crash-recovery check on a device (no device connected on 2026-11-13).
- Docs pass done: CLAUDE.md, README, BLOCKING_POLICY, HOW_IT_WORKS, TRANSLATION_REVIEW_1.3,
  proposed RELEASE_NOTES_1.3.0.

### 2026-11-16 (new session)

- Read the parts skipped in Part 1 (AppSecurityMonitor, DnsAnomalyDetector, LocalDataEraser,
  ConnectionsCsv, the four workflows, the ADRs). New items:
  - N-1 DNS anomaly memory: a set of every distinct name looked up since the start, for an
    unused count, grew without bound; and the alert de-duplication map could grow past its
    cap during a flood of new names. Set removed; the map now drops its oldest half when
    still over the cap (the database keeps alerts to once a day). DnsAnomalyMemoryBoundTest
    failed before (9,131 keys held, cap 5,000), passes now.
  - N-2 (reported, not changed) "Clear all data" does not drop the SIEM queue held in memory
    (HTTPS inspection code; owner's decision).
  - N-3 nightly `live-lists`: the checkout left its `issues: write` token in .git/config for
    the Gradle build; now `persist-credentials: false`.
  - Doc nits: stale file references in ADR 0009 and 0011 (Proposed) fixed; ADR 0007
    (Proposed) notes ADR 0017; stray double space in `class DnsHandler`.
- /cso: not assessed (its trusted launcher is not installed).
- V-18 crash recovery run on the emulator: restarts in about 6.5 s, a worker failure
  rebuilds in 45 ms. Found and fixed: no notification at all when Android refused the
  restart after repeated crashes. Results in docs/security-test-2026-10-14.md (V-18).
  Still open: no signal when notifications are denied (N-4, proposal for the owner).
- Checks after today's changes: 1,289 unit tests, 0 failures (one run failed once in
  WhileObservedTest, a real-time-delay test not touched today; 5/5 alone and the full re-run
  pass); lint 0 errors (27 warnings, none on changed lines); R8 passed; androidTest compiles.
- Hot path (quiet machine, 5 runs): SNI check 3.37-4.08 µs, median 3.41 (baseline 3.39); QUIC
  gate 9.45-9.92 µs (baseline 9.56). No regression.

### 2026-11-16, owner's decisions on N-2 and N-4 (after fba0909)

- N-4: Home card while notifications or the alerts channel are off ("Notifications are off.
  If protection stops, CoreLock can't warn you."), not dismissable, button opens Android's
  notification settings for CoreLock (`NotificationCheck`). Tests first: NotificationCheckTest,
  HomeLogicTest (card order), HomeScreenTest; without the logic 4 tests fail. Checked on the
  emulator: card shown with notifications revoked, button opens AppNotificationSettings, card
  gone after allowing them again. Strings in 4 languages (machine translations, in
  TRANSLATION_REVIEW_1.3).
- N-2: "Clear all data" empties the SIEM queue in memory, and a batch being uploaded at that
  moment is not put back if its upload fails. SiemQueueClearTest: 2 of 2 fail without it.
- ADR 0017 accepted. Version 1.3.0 / versionCode 4. RELEASE_NOTES_1.3.0 finished for users
  (generic wording for security fixes; `<APK_SHA256>` and `<APK_SIZE>` left for the owner).
- Lint: 27 warnings, the same 27 (same types) as on a0ab4a1 when run today; the 27th against
  the earlier note of 26 is TrustAllX509TrustManager reported inside the BouncyCastle jar
  (bcpkix-jdk18on-1.86), not in our code and not new in this branch.
- Checks: 1,296 unit tests, 0 failures; lint 0 errors; R8 passed; androidTest compiles.
- V-18 second run on the emulator (docs/security-test-2026-10-14.md): Power saving on, tunnel
  back at +7.3 s; lockdown on, back at +6.8 s with 0 of 45 TCP probes through; without
  lockdown 43 of 43 went out unfiltered during the gap (the documented kill-switch limit);
  restart refused by Android under lockdown: 0 of 447 through, notice stayed up. Android's
  "crashed quickly" window is about 2 minutes. Emulator restored afterwards (no Always-on,
  Power saving off). Still open: overnight battery and the phone. Docs only; no code change.
