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
| 8 | Navigation: 4 tabs, old routes, What's new (D12), 200 % | To do |
| 9 | Onboarding, splash, vector icon | To do |
| 10 | Polish: polling loops → Flow, one count source, tokens, audit findings | To do |
| — | Release prep 1.1.0 | To do |

## Next

Phase 7 is done. Next is Phase 8 (navigation: 4 tabs, old routes, What's new; delete the 1.0
`SettingsScreen.kt` once the emulator shows nothing links to it). Still open for the owner: D2
(ADR 0013, a separate task after Phase 8) and the rule-name language question (Phase 6 › Open).

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
- The 1.0 `SettingsScreen` composable has no caller now. Not deleted (CLAUDE.md §14.2): proposed.

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
