# Bug-fix round 2: progress log

Working log for the second bug-fix round (branch `bug-fixes-2`). Fixes only: no new
features. Security findings are described generically here until they are fixed; details
stay with the owner.

After an interruption: read this file first, then continue from the last open step.

## Part 1: health check and audit (read-only)

- 2026-11-18: started. Branch `bug-fixes-2`, working tree clean. Tags on origin: `v1.0.0`,
  `v1.2.0` (1.2.1 not published).
- Unit tests, lint and R8 started; emulator `emulator-5554` (API 37 image) started.
- Checks on the starting code: unit tests 1,297 / 0 failures (run fresh with `--rerun`);
  lint 0 errors, 26 warnings; `:app:minifyEnterpriseReleaseWithR8` successful; instrumented
  tests on the emulator 23 run, 21 passed, 2 skipped (the nightly live-list tests), all 13
  migration tests among the passes. Database versions unchanged since 1.0.0 (13 and 2).
- Code reading done so far: packet path and parsers, name decision, QUIC gate, DNS handler,
  bypass guard, session limits, HTTPS inspection engine and consent, SIEM endpoint, data
  erase, blocklist pipeline, components and PendingIntents. Findings so far are kept in chat.
- Emulator smoke test of the debug build done (onboarding, Home, Apps, App detail, Activity
  and its sheet, "Something not working?", Settings, Rules, Blocklists, every Advanced and
  Analysis screen, Arabic at 200% font). No crash. One "app not responding" seen while the
  first list download was being stored; cause found (kept in chat). Logcat: no host names,
  addresses or packages at warning or error level.
- `/cso` not available (launcher not installed); security review done by hand.
- Part 1 report given in chat. Waiting for the owner's decisions and "go".

## Part 2: fixes (owner's "go", 2026-11-18)

- B-1 done: screens no longer start database observers on the main thread (one shared
  helper, `tableChanges`, guarded by `DbChangesOffMainTest`). Test failed before the fix,
  passes after; mutation proof done.
- S-3 done: the TLS record gate checks only the major version, as servers do. Tests and a
  fuzz target added (`ClientHelloAssemblerTest`); one assertion that encoded the old
  behaviour was corrected. Failed before, passes after; mutation proof done. SNI check
  median 4.02 → 3.73 µs (JVM, noise about ±10 %). CLAUDE.md §4.1 and HOW_IT_WORKS updated.
- Full checks after B-1 + S-3: 1,301 tests / 0 failures, lint 0 errors, R8 successful.
- S-1 and S-2 done: the service's per-tuple block table moved into `BlockAttempts` (hard
  cap, oldest first) and a rule's hit count goes up once per attempt (same 30 s window as
  the history row). Rules screen wording "Blocked N attempts" in en, ar, fr, nl (listed in
  the translation review). Tests failed before, pass after; mutation proofs done. Full
  checks: 1,305 tests / 0 failures, lint 0 errors, R8 successful. SNI 3.52 µs, QUIC 10.15 µs.
- S-4 done: at Standard and Strict the QUIC gate drops a hello whose server name is not a
  name, as TCP does (counted as unreadable). Test failed before, passes after; mutation
  proof done. CLAUDE.md §4.1 and HOW_IT_WORKS updated.
- B-3 done: the import skips a rule with "in background" and a site (it could only block
  at all times). Test failed before, passes after; mutation proof done.
- B-5 done: downloaded block lists keep look-alike names (punycode); the refusal is gone.
  Names typed by the user (rules, imports) never went through that check (they use
  RulesLogic.parseSite); a listed look-alike still beats a plain Allow, only a confirmed
  Allow opens it (test). Two tests that encoded the old rule were updated. Mutation proof
  done. HOW_IT_WORKS, ADR 0007 and 0011 (both Proposed) updated.
- B-2 done (fix A): the "blocklist updated" notification, its class and its two strings
  (en, ar, fr, nl) are removed; it used a channel no version created. New test: every
  notification uses a created channel; the old test of the removed notice is replaced by
  "nothing outside NotificationHelper posts a notification". Test failed before, passes
  after; mutation proof done (the old class restored from git for one run, then removed).
- Minor fixes done: the root DNS name is encoded as one zero byte; the four "already
  notified" sets are capped (1,024, oldest first; `BoundedSet`); the fix flow's empty
  step 2 no longer says "nothing was blocked" (blocks without a site name are not
  listed) and no longer shows the "This was blocked" heading over an empty list (4
  languages, translation review updated). Each: test failed before, passes after,
  mutation proof done.
- reloadBlocklists order: finding withdrawn after a re-check. Filters swap atomically and
  the level follows within the same locked call; neither order weakens protection
  beyond the moment of the change. No code change.
- B-4 and the minor documentation items done: the plain HTTP check's limits (first
  request only, common methods only, full URLs only with http://), DNS over TCP not
  served, DoH replies in chunks not understood (BLOCKING_POLICY §6, HOW_IT_WORKS).
- S-5 done (HTTPS inspection; owner's §14.2 approval): inspection now stops with
  protection (`TlsInterception.shutdown`, called when protection stops and before a new
  one is built); the engine closes the connections it decrypts, ends its threads and
  drops its certificate cache, and takes no new work.
- S-6 done (HTTPS inspection): one byte cap (32 MB) over everything inspection holds;
  from three quarters of it new connections pass through undecrypted and are counted
  ("at its limit", Diagnostics), at the cap new chunks are dropped and counted; a
  connection's buffers are freed when it ends. Six mutation proofs, each caught.
- Docs: CLAUDE.md §16.2 cleaned (items 3, 11, 12 shipped and item 10 closed, each kept
  as one line so item numbers used in code and ADRs stay right; item 17's per-list time
  marked done; the 1.2 line of item 19 removed; the 1.2.1 line stays until 1.2.1 ships).
  Release notes, changelog and phone checks (2.6 no freeze, 2.7 attempts) for 1.2.1.

## Part 3: verification
- Full checks: 1,321 unit tests, 0 failures; lint 0 errors; R8 OK. Instrumented tests on
  emulator-5554: 22 run, 0 failures, 2 skipped (nightly-only live-lists test).
- Emulator check found B-1 incomplete: the app still froze when it came back to the
  front during a list refresh. Cause: DAO methods returning a Flow start the same Room
  observer when collected, and five places collected them on the main thread (one at the
  app's root, also rebuilt on every recomposition). All now use `.offMain()`; the guard
  test covers DAO flows too (failed before, passes after; mutation proof done). Repeated
  on the emulator: 4 rounds of back-out, relaunch and screen changes during a refresh, no
  freeze; only a background database thread waited.
- Emulator: an app Block rule shows "Blocked 3 attempts" after one page (fr, nl, ar
  forms correct). A site rule shows no count: DNS-level blocks never counted (known,
  unchanged); phone check 2.7 and FEATURES now say so.
