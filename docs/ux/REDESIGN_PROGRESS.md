# 1.1 redesign: progress log

The working log for the 1.1 redesign (`docs/ux/DESIGN_SPEC_1.1.md`, branch `redesign-1.1`).
After an interruption, read this first and continue at **Next**.

Screenshots: `C:\Users\ooo77\Documents\CoreLock-redesign\<screen>\` (outside the repo).
Nothing here is committed by the agent; the owner commits.

## Status

| Phase | Screen | State |
|---|---|---|
| 1–3 | Design system, Home (before this log) | Done earlier |
| 4 | Activity, detail sheet, fix flow | **Done** (2026-11-03) |
| 4b | D1 pause, Home "Something not working?" | **Done** (2026-11-03) |
| 5 | Apps, App detail, App traffic, Domain detail | **Done** (2026-11-03) |
| 6 | Settings sections, Appearance, Rules, Language, Blocklists, Kill switch, Data (D2), About | **Done** (2026-11-04; D2 waits for the owner, ADR 0013) |
| 7 | Advanced (Network, HTTPS inspection D5, Analysis, Diagnostics, …) | **Done** (2026-11-04; 7a and 7b, emulator checked) |
| 8 | Navigation: 4 tabs, old routes, What's new (D12), 200 % | **Done** (2026-11-04, emulator checked) |
| D2 | Export and import of rules and settings (ADR 0013) | **Done** (2026-11-04, emulator checked) |
| 9 | Onboarding, splash, vector icon | **Done** (2026-11-04, emulator checked) |
| 10 | Polish: polling loops → Flow, one count source, tokens, audit findings | **Done** (2026-11-05, emulator checked) |
| — | Final regression, idle CPU, checks | **Done** (2026-11-04, see the end of this log) |
| — | Release prep 1.1.0 | **Done** (2026-11-04; signed APK and checksum by the owner) |

## Next

Owner's order (2026-11-04, after committing Phases 6–7 as `0db2431`), done without stopping:

1. **Phase 8** (navigation), including:
   - 4 tabs (Home, Apps, Activity, Settings); Payload and Stats tabs removed; every old route
     and notification deep link still works; "What's new: some things moved" card on (D12);
     bottom bar not clipped at 200 % in en/ar/fr/nl; temporary "Security details" row off Home.
   - Rule names (owner said yes): row title from the rule itself (site, app or address); the
     stored label only as a fallback.
   - Overlay taps: the HTTPS inspection consent dialog and the D5 confirmation ignore taps
     that come through an overlay, with a test.
   - Delete the unused 1.0 screens (incl. `SettingsScreen.kt`) only after the parity test and
     the emulator show nothing links to them.
2. **D2** export/import (ADR 0013 first; warn that the file is private; reuse the parser limits).
3. **AT THE END** (original task):
   1. Full emulator regression (debug): top tasks with tap counts; parity table screen by
      screen; notifications, deep links, Quick Settings tile; protection on/off; level change;
      rule change applied live; pause + auto-resume (also after killing the app); D2 round
      trip; language switch; 200 % font; light and dark.
   2. Idle CPU on Home and Activity, before/after (android-profiler). Above 10 % of a core is
      a blocker: fix it.
   3. /health; unit tests, lint, `:app:minifyEnterpriseReleaseWithR8` green. (/cso: skipped by
      the owner on 2026-11-04; do not install anything.)
   4. Release prep 1.1.0 (no signed APK): versionName 1.1.0 / versionCode 2;
      `RELEASE_NOTES_1.1.0.md` (1.0.1 notes + redesign, user words, fingerprint,
      `<APK_SHA256>` placeholder, `CoreLock-1.1.0.apk`), `git rm RELEASE_NOTES_1.0.1.md` and fix
      links; `RELEASE_NOTES_1.0.md` restored from `v1.0.0` (+ fingerprint and published checksum
      23752350...94dd if missing, + "Superseded by 1.1.0. See RELEASE_NOTES_1.1.0.md." on top);
      README (version, APK name, notes link, screens); phone test doc → `docs/PHONE_TEST_1.1.0.md`
      (git mv; update from 1.0.0 + new screens; fix links).
   5. Last: `git rm -r --cached .skills` + `.skills/` in `.gitignore` (files stay on disk);
      listed as the last commit.
4. Final report (9 parts: built per screen; skills; own decisions; behaviour changes not made;
   test/lint/R8/CPU numbers; audit findings; screenshot folders; known bugs; commits in order).

---

## Phase 4: Activity (done 2026-11-03)

### What was built

- **Activity tab** (`ui/activity/`): Blocked / All, groups Just now / Today / Yesterday /
  Earlier, plain rows ("Google ads", app, "Blocked · Ads and trackers list", time),
  repeats merged into one row with "· N times".
- **Detail sheet**: what and why in plain words, the D3 line for lookups, actions in
  order (Allow for this app / Allow everywhere / Keep blocked / Allow anyway / Remove my
  rule / Block for this app / Block everywhere), the malware warning before any Allow of a
  name on a malware list (checked at tap time), Undo for every rule change, collapsed
  technical details (Stopped by, name, IP, port, protocol, security, transferred, package,
  rule, time, "More about this name").
- **Fix flow** "Something not working?": 3 taps, no typing; app → names (most likely
  cause first) → decide. "Not listed or not sure" lists every blocked name of the hour.
- **D8**: `ActivityRules` builds the user rules (HIGH_PRIORITY, normalized names, fixed
  ids, so a second tap changes nothing and Undo knows what to delete).
- The 1.0 Traffic screen stays reachable: Activity › ⋮ › Live connections (with a back
  arrow now) until Phase 8.

### Decisions (own, documented here)

- "All" leaves out allowed lookups: every visit is a lookup and a connection, so each
  visit would show twice.
- Lookup-service blocks (DoH, DoT, plain DNS that skips the resolver) get only "Keep
  blocked". Port 853 and plain DNS on port 53 over TCP/IPv6 can never be allowed
  (`DnsBypassGuard.kt:21`, `PrivacyVpnService.kt:714`, `:771`): label "Blocked · Never
  allowed". DoH over 443 can be allowed in Rules: label "Blocked · Skips CoreLock".
- A name is offered **per app** only when every attempt was a connection of one known app.
  Blocked also as a lookup, or by several apps → "everywhere" only (D3; review finding).
- "Allow anyway" on a malware name is as narrow as where it was found (per app when the
  app is known), with `overridesSecurity`.
- The reason shown is today's reason (ADR 0010), worked out at display time; ad-SDK
  blocks are shown as the ads list.
- Rows that are an address only, for a lookup service, are titled "Private lookup
  service"; legal forms are removed from company names ("Google LLC" → "Google").
- The list holds the newest 500 rows; a note says so.
- Appearance preference added (D4 groundwork): dark until Phase 6 sets the default to System.

### Bugs found and fixed (each with a test that fails without the fix)

| Bug | Root cause | Fix |
|---|---|---|
| Rotating on any tab went back to Home | `LaunchedEffect(onboardingCompleted)` in `AppNavHost` re-ran on recreation and navigated to "dashboard" whenever the route differed | `onboardingRedirect()` moves only on a real mismatch; `OnboardingRedirectTest` |
| A notification deep link would replay after rotation | `LaunchedEffect(initialRoute)` re-ran | handled once, `rememberSaveable` |
| Light theme: white status-bar icons on white, dark bottom bar | window theme is dark; bottom bar used 1.0 colours | `isAppearanceLight*Bars` from the theme; bar from tokens |
| Fix flow: "Android System, 10 attempts", then "nothing blocked" | step 1 counted IP-only rows, step 2 needs a name | one `fixable()` rule for both steps |
| After Allow, step 2 said "CoreLock is probably not the cause" | empty state did not know the user just fixed it | `ui_fix_all_handled` |
| Undo did not bring the name back in step 2 | handled names were a plain set outside the state | `FixUiState.handled`, `onUndone` |
| "Your security settings" for a plain lookup to the network's DNS | no BlockSource is recorded for that drop | port 53 → "Never allowed" |
| Two identical "Google services" rows | IP-only rows merged by address | merged by what the row shows; named rows still by name |

### Review findings (code review, 10 found, 9 fixed)

Fixed: per-app choice for names several apps hit; DNS-and-connection overlap; full reload
on every write (now two indexed reads decide, engine and labels cached); a once-a-minute
reload (now a clock that regroups rows in memory); the old filter's rows under the new
filter; `!!` on database data; malware answers never refreshed (cleared on rule and list
changes); grouping during composition (`remember`); Live connections without a back control.

Not fixed (needs a schema change, not allowed in this task): an index on
`(wasBlocked, timestamp)` for `newestBlocked`. With the signature check, the scan runs
only when a new block arrives. Listed for the report.

### Tests

- Activity: 25 logic + 18 screen + 8 controller tests; `OnboardingRedirectTest` 3;
  `ProtectionPauseTest` 8; Home +3.
- Full suite at the end of Phase 4: **884 tests, 0 failures**; lint 0 errors (26 warnings,
  unchanged, none in new files). Later: 181/181 in `ui.*` and `vpn.*` after D1.
- Mutation checks (fix removed → test fails → fix restored): malware warning, merge key,
  fix-flow predicate, Undo callback, onboarding redirect, pause cap, pause re-arm,
  per-app rule.

### Emulator (emulator-5554, API 37, 1080×2400)

Used like a user: Blocked/All, detail sheets, Allow → Undo (rule really removed: the name
was blocked again afterwards), fix flow in 3 taps, back through every step, rotation,
process kill and reopen, Chrome to an ad host. Screenshots: `activity\` en/ar/fr/nl dark,
en/ar light, fr/ar 200 %, landscape, fix flow steps 1–3.

Seen at 200 %: the 1.0 bottom bar clips its labels ("Accue", "Réglag"). Phase 8 replaces
it with 4 tabs; to check again there.

### Skills used for Activity

| Skill | Used | What it found or changed |
|---|---|---|
| senior-android-engineer | yes | Framing; D1 design (fail-safe resume paths, refuse where not guaranteed) |
| code-review (medium) | yes | 10 findings above |
| android-cli / adb | yes | install, screenshots, rotation, locale, font scale, appops |
| investigate (as a method) | yes, inline | root causes in the bug table, each proven by a failing test |
| design:design-critique, design:accessibility-review | not invoked as skills | the screenshot reviews were done by hand (rows, wrapping, RTL, contrast in light); they found the reason wrap, duplicate rows, light-theme bars |
| /plan-design-review, /design-review, /plan-eng-review, /review (gstack) | not run | interactive, built around web pages or PR diffs; code-review covered the diff |
| jetpack-compose-performance | not invoked | its main point (no heavy work in composition) was the review's finding 9, fixed |
| testing-setup | not needed | Robolectric and Compose tests already set up |
| edge-to-edge | not invoked | the bar-icon bug was found on screen and fixed directly |
| navigation-3 | not applicable | the app uses Navigation Compose 2.9 |
| /cso | pending | for D1 (next) |
| /context-save | not run | this log is the saved context |

---

## Phase 4b: D1 pause and the Home button (done 2026-11-03)

### What was built

- `app/vpn/ProtectionPause.kt`: pause = the existing deliberate stop + a stored end time
  (synchronous write, before the stop) + an exact allow-while-idle alarm to a
  non-exported `PauseReceiver` + a countdown notification with "Resume now". It ends by
  any of: the alarm, Resume now, boot or app update (`BootReceiver`), a check at every app
  process start (`PrivacyGuardApplication`), the user's own switch (the service ends the
  pause when the tunnel is up). Never more than 5 minutes ahead, also if the clock moves.
- Offered only when its end is guaranteed: not without "Alarms & reminders" access
  (Android 12+; the fix flow explains it and opens the setting), not under Always-on VPN
  (Android would restart the VPN; with lockdown a pause would cut the internet), not while
  protection is off.
- Home: hero state "Protection is paused · Back on at 21:52" (amber, tap resumes);
  "Something not working?" button under the numbers (§6.1).
- Fix flow: the pause button under steps 1 and 2.

### Decisions

- Safer option where the guarantee can't hold: no pause, say why (rule 4 of the task).
- The pause notice is not muted by the Notifications switch, like "Protection stopped":
  it says protection is off. `NotificationSwitchTest` lists it as exempt, with the reason.
- If Android refuses to restart the service when a pause ends (it can, from the
  background), "Protection stopped" is shown: never silently off.
- A force stop deletes the alarm; the pause then ends at the next app start at the latest.
  Android allows nothing earlier after a force stop (documented limit).

### Security review (D1; /cso scope done by hand)

- Entry points: none new from outside. `PauseReceiver` is `exported="false"`, explicit
  action check; the alarm and "Resume now" `PendingIntent`s are explicit and immutable;
  "Resume now" starts the service's own start intent, as "Protection stopped" already did.
- No other app can start a pause: `pause()` is called only from the fix flow UI.
- MDM: managed configuration has no "protection can't be turned off" key; a managed
  phone uses Always-on + lockdown, where pause is refused.
- Red-flag scan (§4.3) on all changed files: no new hits (existing service state logs
  only; the debug receiver is not in release).

### Emulator results (all verified)

| Case | Result |
|---|---|
| No alarm access | footer explains, "Open settings" opens Android's page; back → Pause offered |
| Pause | tunnel down, alarm at end time (exact, `exactAllowReason=permission`), countdown notification |
| Process killed during the pause | alarm at 21:40:28 in a new process, tunnel up 21:40:31, notice gone |
| Resume now | tunnel up, notice gone, alarm cancelled (`Reason=alarm_cancelled`) |
| Reboot during the pause | tunnel up 40 s after boot (before the end time), pause cleared |
| Tap the paused hero | resumes, notice gone |

Screenshots: `pause\` (en, ar).

### Tests

`ProtectionPauseTest` 8 (decide, clock cap, availability, re-arm, no alarms → resume,
overdue → start or tell, cancel/start clear everything), Home +3, fix flow +1.
Full suite: 899 tests, 1 failure found (`NotificationSwitchTest`, fixed as above).

### Skills used for D1

| Skill | Used | What it found or changed |
|---|---|---|
| senior-android-engineer | yes | fail-safe design: independent ways back, refuse where not guaranteed |
| android-intent-security / android-permissions-security | applied by hand | non-exported receiver, explicit immutable PendingIntents; no new permission (SCHEDULE_EXACT_ALARM and RECEIVE_BOOT_COMPLETED existed) |
| /careful, /guard, /freeze | not invoked | the service change is one line (`onProtectionStarted`); reviewed by diff |
| /cso | done by hand (above) | no findings |
| anr-audit-and-fix | not needed | pause work is two prefs writes and an alarm on the main thread; `commit()` is a small file |

## Phase 5: Apps and App detail (done 2026-11-03)

### What was built

- **Apps** (§6.3): search (name or package), filters All / Blocked / Active now, a Sort menu
  (Most blocked, Name, Most data, Background activity, Highest risk, Most destinations), one
  row per app with its status, blocked today and one plain risk word (D9), and a "could watch
  you" banner that sorts by risk. Loading, empty (per filter and search), and error with Try again.
- **App detail** (§6.4): a header with status, blocked today and a tappable risk word that
  explains itself; "Internet access" as Allowed / Blocked in background / Blocked, with Undo and a
  Usage Access banner when background blocking can't work; "Connected recently" (24 h, one row
  per name, grouped by organisation or purpose, tap → the Activity sheet with its Allow/Block,
  malware warning and D3 rule); Built-in trackers; Technical details (collapsed) holding every 1.0
  tab: package, grade, permissions, permissions-vs-traffic findings, behaviour and timeline, top
  names, every name, inspected content, and "See every connection" (App traffic).
- **Domain detail**: a "Now" card with the reason and the same Allow/Block choices as Activity
  (`ActivityRepository.nameNow`); per-app choices only when opened from an app
  (`domain/{host}?app=`; the old route still works).
- **Pushed reloads, no polling**: both ViewModels reload on Room invalidation, rule changes,
  installed-app changes, live sessions and a minute tick, at most once every 5 s; errors are
  logged and shown (the 1.0 3 s poll with an empty catch and the `while (true)` loop are gone).
- **D8 helper** (`AppsLogic.changeTo` / `reverseOf`, `AppsRepository.apply`).

### Decisions

| Decision | Why |
|---|---|
| App status comes from app-level rules only (package, no name/IP/port/protocol) | 1.0 called an app "blocked" when one of its names was blocked, and "Allow" deleted every Deny rule of the app, including per-name rules |
| One status at a time; Blocked removes the background rule and the other way round | two rules for one app made the label ambiguous |
| New rules are written before old ones are removed | each write reaches the engine at once; the app is never briefly left unblocked (fail closed) |
| Disabled rules are never touched | the user switched them off on purpose |
| Undo after every status change | a mis-tap must be cheap to fix |
| Sorts: the spec's four plus 1.0's Highest risk and Most destinations | parity; the 1.0 "High risk" filter is covered by the risk word, the risk sort and the banner |
| Risk word explained in App detail only, not by a button inside the list row | on the emulator a tap near the row's middle hit the nested button instead of opening the app |
| "Uses trackers" uses 1.0's suspicious count (profile risk ≥ 40) | existing data; no new behaviour |
| App traffic and Domain detail bodies keep the 1.0 card style | moved to Phase 10 polish; they work and are reachable |

### Bugs found and fixed

| Bug | Cause | Fix and proof |
|---|---|---|
| Tapping the middle of an app row opened the risk explanation, not the app | nested clickable in the row | removed from the row; `AppsScreenTest` taps the row centre (fails with the nested button) |
| "Allowed" removed the user's per-name rules for the app | 1.0 deleted every Deny rule of the package | `isAppLevelBlock`; tests keep name and disabled rules; removing `matchDomain == null` makes 2 tests fail (mutation check done) |
| A connection blocked by a rule that was later removed read "Your security settings" | no source is stored for rule blocks; the fallback named settings only | wording "A rule or setting", explanation says it may no longer apply (4 languages) |
| "about 0 per day" for a name seen 3 times in 7 days | integer format of a rate under 1 | "less than once a day" below 1 (4 languages) |
| Switching Blocked ↔ Blocked in background left the app with no rule for a moment | delete before write | write first; `AppsLogicTest.between_two_blocked_statuses…` checks the logic it relies on (the repository order itself is checked by review, not by a test) |

### Tests

New: `AppsLogicTest` 25, `AppDetailLogicTest` 7, `AppsScreenTest` (list and detail),
`DomainNameNowTest` 5; `AppsRiskWindowTest` updated; `ThemeTokenUsageTest` legacy list shrank
(AppDetailScreen). Full suite: **951 tests, 0 failures**; lint **0 errors**, 26 warnings (none new).

### Emulator (emulator-5554, API 37) — all verified

- Blocked: Chrome's connections stopped ("Blocked · Your rule", 12 blocked today); Allowed again:
  example.com loads. Snackbar and Undo shown each time.
- Blocked in background: Usage Access banner shown; "Open settings" opens Android's Usage
  Access page; Home shows the same warning.
- Landscape, and kill and reopen: status kept.
- Blocked filter shows only Chrome; Sort menu with check mark; sort by Name works.
- Domain detail from the app: lookup-blocked name offers only "Allow everywhere" (D3).
- Screenshots: en/ar/fr/nl dark, en/ar light, fr/ar/nl 200 %, landscape, sort menu
  (`CoreLock-redesign\apps\`). At 200 % the filters stack and the status control stacks;
  no clipping inside the screens. The tab bar labels clip at 200 % in fr (Phase 8 item).

### Skills used for Apps

| Skill | Used | What it found or changed |
|---|---|---|
| senior-android-engineer | yes | rule-write order (fail closed), app-level vs per-name rules |
| android-cli / adb | yes | install, screenshots, rotation, locales, font scale, appops, settings intent |
| investigate (as a method) | yes, inline | each bug above traced to its cause before the fix |
| code-review (by hand, diff) | yes | the write-order finding; no other defects |
| design:design-critique, design:accessibility-review | by hand | nested tap target, 200 % stacking, RTL mirroring checked on screenshots |
| /plan-design-review, /design-review, /plan-eng-review, /review | not run | built for web pages and PR diffs; the hand review covered the diff |
| jetpack-compose-performance | applied | filtering and sorting are remembered on their inputs, not run per recomposition |
| testing-setup | not needed | Robolectric and Compose tests already set up |
| /cso | not needed | no permission, intent, inspection, export or pause change in this phase |
| /context-save | not run | this log is the saved context |

## Phase 6: Settings (done 2026-11-04)

### Done so far

- New Settings list (§6.5): Protection (level sheet, Rules, Blocklists, Kill switch, Restart after
  a crash), Notifications, Language, Appearance (Theme sheet, D4), Data (Keep history for, Export
  history with a "keep it private" sheet, Allow screenshots, Clear all data with a confirmation
  sheet and then the device lock as in 1.0), About (Version, What CoreLock can and can't do,
  Blocking policy, licenses, See new versions, Report a problem, D11), and Advanced.
- New screens: Settings › Blocklists (names, last update, Refresh now) and Settings › Kill switch.
- Advanced opens the 1.0 Settings screen whole until Phase 7 splits it (route `advanced`), so
  every expert setting stays reachable. Its title still says "Settings" (Phase 7).
- `HistoryExport`: one CSV export for Settings and Statistics (same `ConnectionsCsv` escaping).
- `SettingsLogic`: list update time (the oldest success, so a stale list isn't hidden), kill
  switch words (never "On" unless Android says so), appearance default shared with MainActivity.
- `SettingRow`: value-only rows (no chevron) and a danger title.
- D2 (export/import of rules and settings): **not built.** ADR 0013 (Proposed) records why: D2
  was approved on the condition that import reuses an existing parser with safety limits, and
  the code has none for rules. Needs the owner's decision.
- Tests: `SettingsLogicTest` 7, `SettingsScreenTest` 14 (every spec row present, confirmation
  before clear, export warning, theme, retention, lists text, version, report link, Advanced,
  kill switch screen, refresh disabled while downloading). Settings package: 27 tests, 0 failures.
- Emulator: all rows open the right place (Android share sheet, VPN settings, browser links);
  theme changes at once; screenshots en/ar/fr/nl dark, ar light, fr/ar 200 % in
  `CoreLock-redesign\settings\`.

### Rules and allowed sites (done 2026-11-04)

- `RulesScreen.kt` rewritten on the tokens: top bar with back and "Add rule", Suggested
  section, search, "Your rules · N", one row per rule (name; what it does in words with an
  icon; what it matches: site, app name, address; times used; switch; 48 dp delete).
  Loading, error with Try again, empty, and "no rule matches" states. A malware override
  reads "Allows, despite the malware warning" in the danger colour, never like a plain Allow.
- Delete shows "Rule deleted" with Undo (writes the same rule back, switch state included).
  The message lives in the ViewModel, so it survives rotation.
- Add sheet (`CoreLockBottomSheet`): Site / App / Address, the value, Block / Allow. Add is
  enabled only for a valid value; a wrong value is explained (1.0 dropped a bad address
  silently). A pasted browser address becomes its site name (`RulesLogic.parseSite`:
  scheme, path, port, user part removed; lower case, no trailing dot; international names
  in ASCII). The malware warning still comes before any Allow, checked at tap time.
- `RulesViewModel`: every write is caught and said on screen ("couldn't be saved"); 1.0 had
  no catch, so a database error crashed the app. App names read off the main thread.
- Strings: `strings_rules_ui.xml` (32, four languages; machine translations in
  TRANSLATION_REVIEW_1.1.md with the Settings table).

Decisions: user rules may target a top-level domain (`*.xyz`), unlike downloaded lists; names
with "_" and packages without a dot are refused; App rules still take a typed package name,
and the hint points to Apps for installed apps (no app picker: new scope).

Tests: `RulesLogicTest` 9, `RulesScreenTest` 11. The 48 dp test failed on the first run (the
M3 icon button measured 40 dp) and passed after the fix. Full suite **993 tests, 0 failures**;
lint 0 errors, 26 warnings (unchanged). `ThemeTokenUsageTest`: RulesScreen left the legacy list.

Emulator (API 37): add from a pasted URL → `ads.example.com`; switch off → "Blocks · Off";
delete → Undo → rule back, still off; Allow on a malware-seed name → warning → "Allow anyway"
→ override row. Arabic at 200 %: mirrored, nothing cut. Screenshots in `CoreLock-redesign\rules\`.

### Open (owner's decision)

- **Rule names keep the language they were made in.** Every screen stores `label` as text at
  creation (1.0 design), so after a language change the Rules list mixes languages; the
  suggestion text also stays in the old language until the screen reloads. Option: show the
  row title from the rule itself (site / app name / address) and keep `label` only as a
  fallback. Not done: it changes what every rule row says.

## Phase 7a: Advanced (done 2026-11-04)

### What was built

- `ui/settings/AdvancedScreens.kt`: Advanced (four rows; the warning under the title),
  Network, Use two proxy servers, Analysis, Diagnostics. Route `advanced` no longer opens the
  1.0 Settings screen; new routes `advanced/network`, `advanced/proxies`, `advanced/analysis`,
  `advanced/diagnostics`. HTTPS inspection opens the existing `payloads` screen (7b restyles it).
- Every 1.0 expert setting is on one of them (parity rows S2–S4, S11–S13, S15–S19, S22–S24,
  S26–S28), using the same preferences and `SettingsViewModel`; no setting changed behaviour.
- `AdvancedLogic.kt`: DNS presets, port and proxy host checks, list names.
- `SettingsHomeScreen.kt`: its card helpers are internal (`SettingsCard`, `SettingsDivider`,
  `group`) so Advanced looks the same.

### Bugs fixed on the way (each with a test)

| Bug | Fix | Proof |
|---|---|---|
| Clearing a proxy port saved 1080 at once, so typing a new port fought the field; ports above 65535 were saved | the field keeps the text, only 1–65535 is saved, a wrong port is explained | mutation check: 1.0 behaviour back → `clearing_the_port…` fails ("wrote 1080 while empty: [1080, 9050]") |
| The DNS server was saved on every keystroke that parsed ("1.1.1.1" on the way to "1.1.1.10") | sheet with presets and "Other server"; saved by the button, only a complete IP | `a_typed_dns_server_is_saved_only_when_complete…` |
| Encrypted DNS gave no hint that a blocked DoH server stops all sites (ADR 0012) | note under the switch while it is on, naming the provider | `encrypted_dns_on_names_the_provider…` |
| Proxy chain switched on with a server missing looked like it worked | "Fill in both servers for the chain to work." | `the_proxy_screen_says_when_a_server_is_missing` |

### Decisions

- Diagnostics shows each check as icon + word (Pass / Warn / Fail), not the 1.0 ALL CAPS pill.
- "Security systems" opens the 1.0 Home (`securityOverview`, which also holds the night report
  and recommendations) until those cards get their own screen (Phase 10).
- "Unencrypted connections" is the blocking switch only; the list of recent unencrypted
  connections (parity R6 Cleartext filter) stays in Live connections until Phase 8 retires it.
- The 1.0 `SettingsScreen` composable has no caller now. Deleted in Phase 8 (owner's go-ahead), after the parity test and the emulator.

### Tests

`AdvancedLogicTest` 6, `AdvancedScreensTest` 14. Full suite **1013 tests, 0 failures**; lint
0 errors, 26 warnings (unchanged); the one new hint (mutableIntStateOf) fixed.

### Emulator (emulator-5554, API 37), 2026-11-04 — verified

en, ar, fr, nl at 100 %, ar and fr at 200 %: Advanced, Network (both halves), DNS server sheet,
Use two proxy servers, Analysis, Diagnostics (all parts); screenshots in
`CoreLock-redesign\advanced\`. Fixed after the review: the proxy explanation touched the card
above (padding); the External IP check, when skipped, now names "Public IP check"; "Names per
list" showed raw keys ("hagezi-tif-mini", "BuiltIn-ads") and ungrouped numbers, now the
published list names, "Built-in: Display ads" and plurals with grouping (3 new strings, test
`stored_list_keys…`). Arabic is RTL throughout; nothing clipped at 200 % inside the screens.

## Phase 7b: HTTPS inspection (done 2026-11-04)

### What was built

- `ui/mitm/MitmScreen.kt` rewritten with tokens only; Analytics and the capture detail moved to
  `ui/mitm/MitmDetailScreens.kt`. Off the `ThemeTokenUsageTest` LEGACY list. Same captures,
  filters, CA card (install, regenerate, remove old, save .crt), QUIC switch, status card,
  Analytics and the four detail tabs (Payload / Headers / Risk / Fields). One scrolling list,
  so at 200 % the captures are not pushed off screen by a fixed header.
- Title "HTTPS inspection", with a back arrow when opened from Advanced (none from the 1.0
  tab, until Phase 8). The two unlabelled icon buttons now read "Install the inspection
  certificate" and "Analytics"; direction arrows read "Outbound" / "Inbound"; flagged rows say
  "Flagged", not only a red bar.
- **D5:** switching on always asks one question first. Without valid consent it is the 1.0
  consent dialog (text and buttons unchanged); with consent, "Turn on HTTPS inspection?". Only
  the answer turns inspection on; Cancel leaves it off. Switching off asks nothing and, as in
  1.0, deletes the CA key and asks the user to remove the installed CA.
- The JSON colouring of a payload copied the rest of the text at every character (about 500 M
  character copies for a 32 KB body, which the server controls); now linear.

### Tests

`HttpsInspectionScreenTest` 9 (D5 both ways, decline, switch off, JSON colours, a large body,
unterminated strings). Mutation check: the switch turning inspection on directly (1.0) → 4 of
9 fail. `BrandNameTest` now checks the new title instead of the removed "CORELOCK PAYLOAD"
header. Full suite **1023 tests, 0 failures**; lint 0 errors, 26 warnings (unchanged).

### Emulator, 2026-11-04 — verified

Cancel on the confirmation: `mitm_enabled` stays false (read from the app's prefs). Turn on:
true; the CA card and QUIC switch appear. Switch off: false, remove-CA dialog. Consent removed
by hand: the switch shows the consent dialog, not the confirmation; Cancel leaves it off.
Accepting consent on the emulator was not run (the command was declined); the unit test covers
it. Screenshots en, ar, fr, nl, and ar and fr at 200 %, in `CoreLock-redesign\https\`.

## Phase 8: Navigation (done 2026-11-04)

### What was built

- **Four tabs:** Home, Apps, Activity, Settings (`APP_TABS` in `AppNavHost.kt`; rounded
  icons). Stats and Payload left the bar; their routes `statistics` and `payloads` stay, opened
  from Settings › Advanced (and the What's new card).
- **Every 1.0 route still works.** The 1.0 screens opened from Advanced (Statistics, Security
  analysis, Security systems, Ads, Ad privacy, licenses, license text) get a back arrow in
  their title row (`LocalScreenBack` in `PrivacyGuardUi.kt`, set by `Pushed` in the nav host).
  Opened alone, from a notification, Back goes Home.
- **What's new (D12) on:** shown once after an update from 1.0; either link or the close button
  ends it.
- **Bottom bar at 200 %:** the label shrinks only when a word doesn't fit, never below its
  100 % size, with a 4 dp side gap (`TextAutoSize.StepBased`); "Instellingen", "Réglages" and
  "الإعدادات" fit.
- The temporary "Security details" row is off Home (Advanced exists).
- **Rule names (owner's decision):** a rule row's title is its site, address or app
  (`RulesLogic.titleOf`); the stored label only when the rule matches none of these. Search
  still finds the label.
- **Overlay taps (owner's decision):** the HTTPS inspection consent dialog and the D5
  confirmation ignore taps that come through another app's window
  (`IgnoreObscuredTouches()` in `ui/components/ObscuredTouches.kt`:
  `filterTouchesWhenObscured` on the dialog's view and its parents).
- **Deleted:** `ui/settings/SettingsScreen.kt` (1.0 Settings, 882 lines). Every action on it is
  reachable in 1.1 (checked call by call: level, rules, lists, kill switch, notifications,
  details, weekly report, screenshots, auto-restart, retention, export, clear data, language,
  DNS, DoH, QUIC, cleartext, weak TLS, CT, external IP, proxies, diagnostics, integrity,
  battery, licenses, releases page). `KillSwitchTest` now checks the 1.1 Kill switch screen.
  Its ~70 `set_*` strings are now unused (lint ignores unused resources here); left in place,
  listed for Phase 10 cleanup. Only text lost: the TLS-fingerprint paragraph under the 1.0
  Analysis links; Security analysis has its own TLS-fingerprint section.

### Tests

- `NavigationParityTest` 6: every 1.0 route of the parity table and every new 1.1 route is
  declared; `NOTIFICATION_ROUTES` are exactly alerts, crypto, connections, apps and all
  declared; the bar is the four tabs in order; every route has a way in and nothing navigates
  to an undeclared route. Mutation: renaming `crypto` → 3 of 6 fail.
- `HttpsInspectionScreenTest` +2 (an obscured tap neither confirms nor consents; the same tap
  unobscured does). Mutation: without the guard → 2 of 11 fail.
- `RulesLogicTest` +1, `RulesScreenTest` +1 (title from the rule, not a label in another
  language).
- Full suite **1033 tests, 0 failures**; lint 0 errors, 26 warnings (unchanged).

### Emulator (emulator-5554, API 37), 2026-11-04 — verified

Four tabs. What's new card shown; "Open Statistics" and "Open HTTPS inspection" open them with
a back arrow; Back returns Home; the card is gone afterwards. Deep links (`pg_nav_route`):
alerts → Alert inbox, crypto → Security analysis, connections → Activity, apps → Apps; Back
from alerts/crypto → Home; `statistics` and an unknown route → Home (not in the allow-list,
unchanged). Back arrows on all five Analysis screens, licenses and license text. Bar at 200 % in
en, ar, fr, nl (dark) and en (light): no label cut or touching. Screenshots in
`CoreLock-redesign\navigation\`.

### Skills used for Phase 8

senior-android-engineer (navigation parity, tapjacking guard), android-cli/adb on emulator-5554
(walkthrough, screenshots). No other skill; nothing installed.

## D2: export and import of rules and settings (done 2026-11-04)

ADR 0013 accepted by the owner ("D2 as planned, with ADR 0013") and updated with what was built.

### What was built

- Settings › Data: "Export rules and settings" and "Import rules and settings".
- Export: a sheet first says the file is private and what is not in it; then the device lock
  (as other exports); then Android's file picker (`corelock-rules-and-settings.json`).
- Import: device lock, file picker, then a question that says what changes ("2 new rules,
  1 block · 1 allow, 1 setting will change", rules already there and invalid entries counted).
  Nothing changes before Import. Refused with a reason for: too large, not ours / damaged,
  newer version, more than 5,000 rules, nothing new, organisation-managed phone (checked at the
  preview and again at Import).
- `ConfigTransfer.kt`: format, limits (1 MiB, 5,000 rules, nesting depth 4 checked before
  parsing, 255-character strings), per-field validation with the add-rule checks, plan
  (duplicates never added, existing rules never replaced), labels and ids written by the app.
  An imported rule never has `overridesSecurity`; a typed DNS server is never carried.
- `RulesRepo.addRules` (one write, one change notice); `SettingsViewModel.blockCleartextRule`
  / `blockWeakTlsRule` shared by the switches and the import.

### Tests

`ConfigTransferTest` 15 (round trip; only the user's rules; override dropped; not ours; newer
version; oversized file and endless stream; deep nesting; rule limit; 16 kinds of invalid rule;
formula-like labels; settings one by one and the typed DNS server; duplicates; app ids; changed
count; JSON null). Mutation: without the nesting check or the "background needs an app" check →
2 of 15 fail. `SettingsScreenTest` +4 (private warning before the picker; import row; the
question and that only Import applies; a refusal says why).

### Emulator (emulator-5554), 2026-11-04 — verified

Round trip, before the device-lock step was added: exported 2 rules (one Allow with the malware
override, one disabled Block) and the settings; the file holds no label, override or history.
Deleted both rules and changed the theme; import asked "2 new rules · 1 block · 1 allow · 1
setting will change"; after Import both rules were back, the Block still off, the Allow
**without** the override, the theme back to Light. Importing again: "Everything in this file is
already on this phone". A version-9 file: "made by a newer version". Arabic at 200 %: export
sheet fits. Screenshots in `CoreLock-redesign\transfer\`.

After adding the device-lock step, the debug build shows "Sensitive actions are disabled
because app integrity could not be verified" (a debug build is not signed with the release key;
"Clear all data" behaves the same, unchanged). The signed build must be checked on the phone:
added to `docs/PHONE_TEST_1.1.0.md`.

### Skills used for D2

senior-android-engineer (untrusted-input design, threat check: file from a chat, MDM, the
malware override), android-cli/adb on emulator-5554. Nothing installed.

## Phase 9: onboarding, splash, icon (done 2026-11-04)

- **Onboarding** (`ui/onboarding/OnboardingScreen.kt`): three screens, "Step N of 3".
  1. What CoreLock does, that history stays on the phone, and what it can't block.
  2. Why Android asks about a VPN; "What leaves this phone" opens the network and storage
     disclosure; the Private DNS warning comes before Android's VPN question.
  3. Level (Standard preselected); battery advice only when Android restricts the app.
  The HTTPS-inspection consent box was removed from onboarding (safer: consent only on the
  HTTPS inspection screen, with D5). `OnboardingScreenTest` (7).
- **Icon:** vector shield with a keyhole (`drawable/ic_launcher_*`), adaptive with a
  monochrome layer (themed icons), a vector mipmap for API 24–25; the robot WebPs are gone.
- **Splash:** `values-v31/themes.xml` (platform splash attributes), launch background in
  `colors_launch.xml` (light) and `values-night` (dark).
- Emulator: splash, launcher, the three steps, the disclosure sheet, Done → Home at
  Standard with the VPN on; Arabic at 200 %. Screenshots: `phase9\`.

## Phase 10: polish (done 2026-11-05)

Owner's decisions of 2026-11-04 and the open audit items.

| Item | What was done | Test |
|---|---|---|
| Exports and the device lock (decision 1) | History export and the Statistics CSV go through `runProtected`, like D2. The CSV export is one button that shares the file (the old "CSV ready" label exported again). | no unit test (the lock is the existing `runProtected`); emulator: debug builds stop at the integrity check, as designed |
| One "blocked today" (decision 2) | `BlockStats.today` / `perAppToday` / `todayCounts` (local midnight to now) in the notification (also refreshed at midnight), Home, Activity ("N blocked today"), Apps, Statistics, Ads and Security systems. The Statistics and Security systems tiles open the Activity Blocked list. | `BlockCountAgreementTest` (5; found a midnight off-by-one); mutation: 24 h window back in Statistics and in Security systems → fails |
| TLS explainer (decision 3) | "What is a TLS fingerprint?" in Security analysis' TLS section. | `TlsExplainerTest` |
| Ad-SDK reason (decision 4) | `ActivityReason.AD_SDK`: "Blocked · An ad tracker built into the app". | `ActivityLogicTest` (+2) |
| "1.0.1" wording (decision 5) | "a later release" in CLAUDE.md, ADR 0011, the security-test doc. | — |
| Polling loops (audit) | 8 loops → `ui/DbChanges.kt` (`changesOf`, `Reloader`): reload when Room reports a change, at most every 2–15 s; a load error shows `LoadFailedBanner` with Try again. | full suite |
| Theme tokens (audit) | `ThemeTokenUsageTest` LEGACY list emptied. | `ThemeTokenUsageTest` |
| Light theme on the 1.0 screens (found 2026-11-05) | The `Pg…` colours were dark-only: Domain detail, App traffic, Alerts, Statistics, Security systems, Security analysis, Ads and others drew near-white text on the light background. They now follow the theme (`LegacyDark` = the 1.0 values, `LegacyLight` from the 1.1 light tokens). | `LegacyPaletteContrastTest` (3); mutation: light text back to near-white → fails |
| Faint text contrast (audit §5) | `PgTextFaint` 2.4–3.3:1 → 4.5:1 or more on every panel. | `LegacyPaletteContrastTest` |
| Plain words (spec §7.1) | Two everyday strings rewritten; App detail's stale "Payload Inspector" hint fixed. | `BannedWordsTest`; mutation: "packet captures" back → fails |
| Ads lists "ON" at every level (audit) | Lists the level doesn't use say "Not used". | `AdsLevelUseTest` |
| Titles (audit) | "Crypto analysis" → "Security analysis"; Security systems was titled "Dashboard", now "Security systems", subtitle "Protection is on". | emulator |
| Unused strings | 158 names nothing used, deleted in all four languages. | build, lint |
| Lint error | Onboarding read `protectionLevel.value` during composition → `collectAsStateWithLifecycle`. | lint 0 errors |

Already solved earlier, checked again: Domain detail "about 0 per day" (now "less than once
a day"); Rules delete button 48 dp; the 10 sp letter-spaced label style (the 1.1 type scale
replaced it); Runtime posture now only under Advanced › Diagnostics.

Not changed (rule 4, for the owner): the TLS-fingerprint alert severity (a Chrome update can
still raise a HIGH alert); the HTTPS-inspection screen's 5 s status loop (MITM code).

### Emulator (emulator-5554, API 37), 2026-11-05

Light: Domain detail, Statistics, Security systems, Alerts, Security analysis, Ad privacy,
Ads detail all readable. Dark: Security systems and Security analysis look as before.
Notification "8 blocked today" = Home 8 = Activity "8 blocked today" (Statistics 5 = Home 5
and Security systems 7 = Home 7 earlier the same morning). Screenshots: `regression2\`.
Not seen on the emulator: the AD_SDK row (needs an app with a listed ad SDK; covered by unit
tests); the device-lock prompt itself (debug builds stop at the integrity check).

Idle CPU, 60 s each, screen on and untouched: Activity 1.2 %, Home 1.1 %, Statistics 1.1 %,
Security systems 1.1 % of one core (audit, release build: Home 42 %, Stats 68 %).


## Release preparation 1.1.0 (done 2026-11-04; no signed APK built)

- `app/build.gradle.kts`: versionName 1.1.0, versionCode 2 (1.0.1 was never published, so
  2 is still the next code after 1.0.0's 1).
- `RELEASE_NOTES_1.1.0.md`: everything from the 1.0.1 notes plus the redesign in user words
  ("What's new", "Where things moved"), the signing fingerprint, `<APK_SHA256>`, file name
  `CoreLock-1.1.0.apk`, known limitations kept (two added: a DNS answer Android already holds,
  and Pause's conditions).
- `RELEASE_NOTES_1.0.1.md` removed with `git rm` (staged). `RELEASE_NOTES_1.0.md` restored to
  the published `v1.0.0` text, which already has the fingerprint and checksum `23752350…94dd`,
  plus one first line "Superseded by 1.1.0".
- README: version, APK name, release-notes link, the Screens table for 1.1, "Security systems"
  for the 1.0 Home section, export/import under privacy.
- `docs/PHONE_TEST_1.0.md` → `docs/PHONE_TEST_1.1.0.md` (`git mv`, staged): update from the
  published 1.0.0 to 1.1.0, the four tabs, What's new, fix flow and pause, export/import with
  the device lock (signed build only), HTTPS confirmation, CPU on Home and Activity.
- `.skills` removed from the index (`git rm -r --cached`, 444 files, staged; the files stay on
  disk) and `.skills/` added to `.gitignore`.

## Final regression on the emulator (2026-11-04, debug build, API 37 emulator)

| Check | Result |
|---|---|
| Protection off / on | 1 tap each on Home; the tunnel follows (`dumpsys connectivity`). |
| Level change | Home › Protection level › Strict: 2 taps; stored and shown on Home. |
| Rule applied live | Settings › Rules › + › name › Block › Add (about 6 taps): a fresh name is blocked at once; delete reopens it. |
| Block an app | Apps › app › Blocked: 3 taps; listed under the Blocked filter; Allowed again in 2 taps. |
| Allow a blocked ad name | Activity › row › Allow everywhere: 3 taps; the row leaves the Blocked list. |
| Fix flow / pause (D1) | Home › Something not working? › Pause: 2 taps; tunnel down, exact alarm set. App process killed (`am kill`); at the alarm protection came back by itself (new process, tunnel up, countdown notification gone). |
| D2 round trip | Export › import on the same phone: override dropped, disabled state kept, theme restored; re-import says nothing changes; a newer-format file is refused. With the device lock added afterwards, the debug build refuses export/import (integrity check, as for Clear data); the signed build is phone-test step 11. |
| Quick Settings tile | Tap: protection off; tap again: on. |
| Notifications | The status notification opens the app; alert deep links (alerts, crypto, connections, apps) open the right screen; the retired statistics and unknown routes open Home. |
| Language | Settings › Language › Nederlands and back to English: 3 taps each, the whole UI switches. |
| Theme | Settings › Theme › Dark: Home, Settings checked. Light checked in Phase 8. |
| 200 % font | Bottom bar and screens checked in en/ar/fr/nl in Phase 8; D2 sheets in Arabic at 200 %. |
| Parity | `NavigationParityTest` (every 1.0 route and notification route reachable); walked screen by screen in Phases 6–8. |

**Found and fixed during the regression:** Activity said "Blocked · Ads and trackers list" for a
name that had been blocked by a user rule deleted since (lookups store no source). The reason
now names a list only when an ad, tracker or telemetry list still has the name (or a parent);
otherwise it says "A rule or setting", whose text already says the rule may have been removed.
`ActivityLogic.reasonFor(onBlockList)`, `BlocklistRepo.isOnBlockList`, `BlocklistDao.anyInCategories`
(parameterized query, no schema change), `ActivityRepository`; test
`ActivityLogicTest.a_name_on_no_list_is_never_shown_as_listed` (fails without the fix: 1 of 26).
Checked on the emulator: iana.org now reads "A rule or setting", Google ads still names the list.

Left on the emulator: level Strict, theme Dark, a test Allow rule for doubleclick.net (the last
emulator command was denied, so it was not removed).

## Idle CPU (2026-11-04, debug build on the emulator, screen on and untouched)

`utime+stime` of the app process over 60 s (`/proc/<pid>/stat`), percent of one core.

| Screen | Before (audit, 2026-10-25, release build) | After |
|---|---|---|
| Home | 42 % | 2.8 % and 2.2 % (first minute after a cold start: 28 %, list loading) |
| Activity | – (Stats: 68 %) | 2.0 %, 1.3 %, 1.5 %, 1.4 % |

Well under the 10 % limit. The emulator runs SQLCipher translated, so the phone figure
(phone-test step) decides.

## Checks

- Unit tests 1,053 / 0 failures, lint 0 errors / 26 warnings, `:app:minifyEnterpriseReleaseWithR8`
  successful (rerun after the Activity fix, 2026-11-04).
- /health: the detector found no Gradle tools, so it was scored by hand from the Gradle run:
  type check (Kotlin compile) clean, lint 0 errors / 26 warnings, tests all passing; dead code,
  shell and gbrain not available.
