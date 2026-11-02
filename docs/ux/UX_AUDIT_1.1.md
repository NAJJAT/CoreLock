# CoreLock UX audit (basis for 1.1)

- **Date:** 2026-11-02
- **Build audited:** the debug build installed on the emulator `Medium_Phone_API_37.0`, built 2026-10-29 10:14 (before the Kill Switch change `12931f5`)
- **Screenshots:** kept outside the repository, in `C:\Users\ooo77\Documents\CoreLock-ux-audit\` (67 files, listed in section 8)

---

**Repository: no file changed.** `git status` still shows only your four uncommitted files from the migration task, and I committed nothing.

**Emulator:** `Medium_Phone_API_37.0`. The audit used the installed debug build of **2026-10-29 10:14**, which is older than the Kill Switch change (`12931f5`); findings about that card describe the old build.

**Screenshots:** 67 files in `C:\Users\ooo77\Documents\CoreLock-ux-audit\`.

**Emulator state I changed and restored:**
- Language: Arabic, French and Dutch, then back to English.
- Font size: 200 %, then back to 100 %.
- Protection: off and on once.
- Chrome's background-block rule: switched on, then off again.
- HTTPS inspection: on, then off. Turning it off **deleted the inspection signing key** on the emulator (by design).

**Not captured:** onboarding, because showing it needs the app data cleared. I reviewed it from the code only.

## 1. The 5 biggest problems

1. **There's no way to allow something from where it was blocked (severity 3).**
   - A blocked connection's details show "Stopped by" but offer only **Block** (`33_task4_connection_detail_en.png`).
   - Domain detail has no action at all (`05_domaindetail_en.png`).
   - Fixing a broken site takes about 11 taps, 5 scrolls, typing the domain from memory, and a trip to the bottom of Settings.
2. **Home is a 5-screen-tall expert dashboard (severity 3).**
   - About 48 elements: 11 "Security systems" cards, PCAP export, weekly report, recent alerts (`01_home_en` to `01e_home_en_scroll4.png`).
   - The privacy score appears twice, and the Settings gear twice (in the header and in the tab bar).
   - What an everyday user needs (status, switch, blocked today, level) takes about 15 % of it.
3. **Actions look like status labels, and numbers disagree (severity 3).**
   - The red "BLOCK" pill on every connection row is a button, drawn with the same `StatusPill` as "BLOCKED" (`ConnectionsScreen.kt:229-230` and `:302-303`; `06b_traffic_24h_blocked_en.png`).
   - Blocklist totals disagree: 287,349 vs 348,267 vs 358,670 (`11_ads_en.png`, `09d_settings_en_scroll3.png`).
   - Blocked counts disagree: the Home tile said 2 and the evidence screen said 4 (`35_task8_blocked_evidence_en.png`).
   - Stats says "2 blocked" next to "Block rate 0%".
4. **Expert features sit in the everyday layer.**
   - "Payload" (HTTPS inspection) is a main tab (`08_payload_en.png`).
   - Settings has about 123 text items mixed together: runtime integrity, upstream DNS, Certificate Transparency, Dual VPN and SOCKS5 next to language and notifications (`09_settings_en` to `09e_settings_en_scroll4.png`).
   - On a debug build, Settings shows "Runtime posture: **FAIL**, Signature mismatch". Release behaviour not checked.
5. **Text breaks in other languages and at large font sizes (severity 3).**
   - French: the "Contenu" chip is squeezed to 27 px wide (`03_appdetail_fr.png`).
   - Dutch: the 5th App detail chip is gone (`03_appdetail_nl.png`).
   - At 200 %: the tab labels are clipped ("Trafi", "Paylc", "Setti") and "Unencry-pted" breaks mid-word (`20_home_font200_en.png`). The Activity and Payload chips disappear (`24_appdetail_font200_en.png`).
   - In Arabic, letter-spaced monospace labels break the joined script (`01_home_ar.png`).

## 2. Screen inventory

E = EVERYDAY, X = EXPERT. Counts come from UI dumps and are rounded.

| Screen | Elements | E / X | Jargon (count: examples) | Screenshots |
|---|---|---|---|---|
| Splash | 1 | – | – (shows the default Android robot icon) | `00_splash_en` |
| Home (Dashboard) | ~48 | 7 / 41 | 14: tunnel, encryption health, throughput, DNS, IPv6, Behavior DNA, Kill switch, Always-on VPN, payload decryption, TLS, metadata, cleartext, PCAP, Wireshark | `01_home_en`, `01b`–`01e`, `01_home_ar`, `01b_home_ar_scroll1`, `01_home_fr`, `01_home_nl`, `20_home_font200_en` |
| Evidence (Home tile drill-down) | ~6 + list | 4 / 2 | 3: DNS, "not found", IP only | `35_task8_blocked_evidence_en` |
| Sleep report | ~14 | 6 / 8 | 3: DNS, resolver, Tracking | `17_sleepreport_en` |
| Alert inbox | ~6 + 5 per alert | 3 / 3 | 3: cleartext, CRITICAL/HIGH, "connecting in a new way" | `16_alerts_en` |
| Apps | 9 + 7 per row | 4 / 12 | 4: package, destinations, cleartext, risk letter grade (A/C, unexplained) | `02_apps_en`, `02_apps_ar`, `21_apps_font200_en` |
| App detail | 13 + tab | 4 / 9 | 6: flags, SDKs, Mismatch, DNS, Payload, permissions mismatch | `03_appdetail_en`, `03b`, `_connections`, `_sdks`, `_activity`, `_payload`, `_ar`, `_fr`, `_nl`, `24_appdetail_font200_en`, `36_task7_background_block_en` |
| App traffic ("Every connection") | ~10 per group | 8 / 2 | 3: DNS lookups, CDN, first-party (each explained, which is good) | `04_apptraffic_en` |
| Domain detail | ~20 | 14 / 6 | 2: DNS, tracker database | `05_domaindetail_en` |
| Traffic (Connections) | 9 + 5 per row | 4 / 10 | 7: TLS_1_3, WEAK_TLS, TLS_1_0, QUIC, IP, :853, Cleartext | `06_traffic_en`, `06b`, `06_traffic_ar`, `22_traffic_font200_en`, `32_task4_blocked_filter_en` |
| Connection detail (sheet) | ~20 | 10 / 10 | 5: DNS name, DNS over TLS, port 853, GeoIP, Route | `33_task4_connection_detail_en` |
| Stats | ~70, 12 sections | 8 / 62 | 9: block rate, encryption health, score factors, network map, organization, heatmap, threat timeline, CSV, IT report | `07_stats_en`, `07b`, `07c`, `07_stats_ar`, `37_task10_export_picker_en` |
| Payload (HTTPS inspection) | ~10 | 0 / 10 | 8: PAYLOAD, POST, GET, HTTPS inspection, CA certificate, QUIC/HTTP3, UDP/443, TLS/TCP, pinning | `08_payload_en`, `08b`, `08c_payload_inspection_on_en`, `08d_payload_off_again_en` |
| Settings | ~123 texts, ~45 controls | 12 / 33 | 25+: Cleartext (HTTP), Weak TLS, QUIC, TCP, DoH, Certificate Transparency, crt.sh, api.ipify.org, Runtime integrity, Keystore, upstream DNS, resolver, Hagezi-DoH-IPs, SOCKS5, TLS fingerprints, … | `09_settings_en`, `09b`–`09e`, `09_settings_ar`, `23_settings_font200_en` |
| Language picker | 7 | 7 / 0 | 0 | `15_language_picker_en` |
| Firewall Rules | 8 | 5 / 3 | 2: IP, cleartext | `10_rules_en` |
| Add rule dialog | 10 | 7 / 3 | 2: Domain, `*.example.com` wildcard | `34_task4_add_rule_dialog_en` |
| Ads & Trackers | ~45 | 8 / 37 | 6: DNS-level, Hagezi-*, OISD, DoH-IPs, Telemetry, "N of N domains enabled" | `11_ads_en`, `11b_ads_en_scroll1` |
| Why am I seeing this ad? | ~40 | 34 / 6 | 3: SDK, attribution, pixels (well explained; the best-written screen) | `12_adprivacy_en`, `12b_adprivacy_en_scroll` |
| Security Analysis (titled "Crypto analysis") | ~30 | 0 / 30 | 12: JA3, JA4, TLS fingerprint, cipher suites, FREAK, LOGJAM, RC4, CT monitor, crt.sh, SOCKS5, … | `13_security_en`, `13b_security_en_scroll` |
| Open-source licenses | ~12 + list | 6 / 6 | 1: license names | `14_licenses_en` |
| Onboarding (code only) | 32 strings | – | not captured | `OnboardingScreen.kt` |

## 3. Top tasks, measured on the emulator

| # | Task | Current path (taps, scrolls, typing) | Target | Main obstacle |
|---|---|---|---|---|
| 1 | Am I protected? | 0 taps: Home status | 0 | On every screen reopen, Home first shows **"Not protected / Protection is paused"** for about 0.5 s while the VPN runs. Reproduced 2 of 2 times with the service running (`31_home_coldstart_flash_en.png`). Cause: `DashboardViewModel.kt:34` (`isVpnActive = false` default) and `:134-139` (the real state is set only after the slow database refresh). |
| 2 | Protection on/off | 1 tap on the Home switch | 1 | The switch is 30 dp tall, below 48 (`DashboardScreen.kt:682-700`). It sits beside "Privacy score" with no "Protection" label (`30_task2_*`). |
| 3 | Change protection level | 1 tap on Home (or Settings) | 1 | The two places use different wording ("Current engine level"). The level description refers to a setting three screens away. |
| 4 | Site broken: why, then allow | Traffic → Last 24 h → Blocked → find the row (no grouping, scroll) → row → read "Stopped by" → Close → Settings → 5 scrolls → Firewall Rules → + → type domain → Allow → Add = **~11 taps, 5 scrolls, typing, 4 screens** | **3** (blocked row → "Allow this name" → confirm) | No Allow on blocked items or on domain detail; the "BLOCK" pill on an already blocked row; Rules buried at the bottom of Settings (`32`–`34_task4_*`). |
| 5 | What one app connects to | Apps → app → "See every connection" = **3** | 2 | The default tab on App detail is "Mismatch" (expert), not connections. |
| 6 | Block one app completely | Apps → app → "App allowed — tap to block" = **3** (or 2 with the unlabelled list switch) | 2 | The list switch has no label (on = allowed?), and a letter grade sits next to it (`02_apps_en.png`). |
| 7 | Block an app in background only | Apps → app → switch = 3, plus Usage Access in Android (about 4 more) = **~7** | 4 | The inline warning is honest and well placed (`36_task7_background_block_en.png`); the permission trip can't be avoided. |
| 8 | What was blocked today | Home → Blocked tile = **1** | 1 | It shows "last 24 h", not "today"; a 6-line explanation comes first; the count differs from the tile (2 vs 4). |
| 9 | Switch app language | Settings → Language → choose = **3** | 3 | Fine. The row is near the top of Settings. |
| 10 | Export my data | Stats → "Export 7-day CSV" → "Share CSV" → share sheet → app = **4+** | 3 | After the first tap the button reads "CSV ready — tap to share", but tapping it **exports again** (`StatisticsScreen.kt:151,155`). Sharing is a second button (`:157-173`). Export is in Stats, not under Data in Settings. Rules and settings can't be exported at all (no export or backup function found in the code). |

## 4. Heuristic findings, by severity

| Sev | Screen | Heuristic | Finding | Evidence |
|---|---|---|---|---|
| 3 | Connection detail, Domain detail | User control and freedom | No Allow for a blocked item, and no action at all on a domain. | `33_task4_connection_detail_en`, `05_domaindetail_en` |
| 3 | Traffic | Consistency and standards | The action "BLOCK" uses the status pill style; on blocked rows it reads like a status. | `06b_traffic_24h_blocked_en`; `ConnectionsScreen.kt:229` |
| 3 | Home | Aesthetic and minimalist design | 5 screen heights; 11 expert cards; duplicate score and gear. | `01_home_en`–`01e` |
| 3 | Home | Visibility of system status | False "Not protected" flash on every reopen. | `31_home_coldstart_flash_en`; `DashboardViewModel.kt:34` |
| 3 | Ads, Settings, Home, Stats, Evidence | Consistency and standards | Totals and counts disagree across screens (287k / 348k / 359k; 2 vs 4 blocked; 2 blocked at 0 % rate). | `11_ads_en`, `09d_settings_en_scroll3`, `35_task8_blocked_evidence_en`, `07_stats_en` |
| 3 | Main navigation | Match with the real world | "Payload" is a main tab (Arabic "المحتوى", meaning "content"; French "Contenu") but only works with HTTPS inspection on. | `08_payload_en` |
| 3 | App detail (fr, nl, 200 %) | Visibility | Chips squeezed or missing; features become unreachable. | `03_appdetail_fr`, `03_appdetail_nl`, `24_appdetail_font200_en` |
| 3 | Alerts, Home | Help users recognize, diagnose, recover | A normal Chrome update fingerprint is shown as **HIGH** "connecting in a new way"; this trains users to ignore alerts. | `16_alerts_en`, `13_security_en` |
| 2 | Settings | Aesthetic and minimalist design | About 123 items in one scroll; Rules, Ads and Security Analysis at the very bottom; tapping the tab again doesn't scroll to the top. | `09_settings_en`–`09e` |
| 2 | Settings | Error prevention / trust | "Runtime posture: FAIL, Signature mismatch, Debug certificate" shown to the user (debug build; release not checked). | `09c_settings_en_scroll2` |
| 2 | Ads & Trackers | Match with the real world | Every list shows "ON", including Strict-only lists at Standard; "N of N domains enabled" means nothing to a user. | `11b_ads_en_scroll1` |
| 2 | Stats | Error prevention | The "CSV ready — tap to share" label is wrong: the tap exports again. | `37_task10_export_picker_en`; `StatisticsScreen.kt:151` |
| 2 | Apps | Recognition rather than recall | Two chip rows (filters and sort) look the same; letter grade and risk level shown together; package names on every row. | `02_apps_en` |
| 2 | App detail | Flexibility / defaults | Opens on "Mismatch"; header with back arrow, icon and share squeezed beside three counters. | `03_appdetail_en` |
| 2 | Rules | Consistency / user control | No back arrow, a different header style, unlabelled "+", and an empty list with no help text. | `10_rules_en` |
| 2 | Payload | Visibility of system status | Unlabelled switch and icons; the switch turned inspection on with no dialog because an earlier consent was still valid. | `08_payload_en`, `08c_payload_inspection_on_en`; `MitmScreen.kt:179-180` |
| 2 | Security Analysis | Consistency | Called "Security Analysis" in Settings but titled "Crypto analysis". | `13_security_en` |
| 2 | Home / Kill switch card (old build) | Match with the real world | The card's switch has the TalkBack label "Protection". Already fixed in `12931f5`; recheck on a fresh build. | `01b_home_en_scroll1` |
| 1 | Splash | Aesthetic | The default Android robot icon. | `00_splash_en` |
| 1 | Home | Consistency | English title "Dashboard" vs tab "Home"; Arabic uses "Home" for both. | `01_home_en`, `01_home_ar` |
| 1 | Domain detail | Match with the real world | "about 0 per day" for 4 connections. | `05_domaindetail_en` |

## 5. Accessibility and quality

- **Touch targets under 48 dp:**
  - The Home protection switch is about 30 dp tall (`DashboardScreen.kt:682-700`).
  - The Rules delete button is 32 dp (`RulesScreen.kt:231`).
- **Missing TalkBack labels:**
  - The Payload header lock, chart and inspection switch have no labels (`MitmScreen.kt:438-439, 460-461`, and the switch at about `:442`).
  - 33 `contentDescription = null` in the UI code (some are decorative, which is fine).
- **Hard-coded styles:** 56 `Color(0x…)` and 91 `fontSize = N.sp` outside the theme.
- **Font style:** `labelSmall` is a 10 sp monospace font with letter spacing 1.2 sp and a fixed line height of 12 sp (`Type.kt:68-74`). It's used for the tab labels and status pills. That causes the clipping at 200 % and the broken Arabic joining.
- **Contrast (computed from theme colours, WCAG AA needs 4.5:1):**
  - `PgTextFaint` `#5A6478` on the panel colours: **2.54–2.87:1**; on the background: 3.27:1.
  - That colour is used 65 times, including the inactive tab labels (`AppNavHost.kt:377-378`).
  - `PgTextMuted` passes (5.0–5.7:1).
- **Largest font (200 %):**
  - Tab labels clipped; "Unencrypted" split mid-word; the alert badge overlaps the bell (`20_home_font200_en`).
  - App detail chips missing (`24_appdetail_font200_en`). Also captured: `21_apps_font200_en`, `22_traffic_font200_en`, `23_settings_font200_en`.
- **RTL:** mirroring is mostly correct (`02_apps_ar`, `01_home_ar`). The problem is letter-spaced text breaking the joined Arabic script.
- **Text length:** French "Contenu" chip squeezed to 27 px; Dutch 5th chip missing and "0 SDK's" wraps; the Dutch "Instellingen" tab label reaches the screen edge (bounds end at 1080).
- **Polling and state:**
  - 8 `while (true)` loops confirmed: AlertInbox `:43`, AppDetail `:177`, Connections `:155`, Dashboard `:132, :147`, Settings `:77`, Statistics `:112, :120`.
  - Dashboard swallows errors (`:141`, `:150`).
  - The "paused" flash is a side effect of this design (task 1).
  - The CSV export writes the file inside `viewModelScope.launch` on the main thread, with no progress or error feedback (`StatisticsViewModel.kt:275-288`).

## 6. Information architecture proposal

**Current tree:** 6 tabs; maximum depth 4 (Apps → App → Every connection → Domain).
- Home: Alerts; Evidence × 4; Sleep report; Settings gear; PCAP; weekly report.
- Apps → App detail (5 tabs) → App traffic → Domain detail.
- Traffic → Connection sheet → App.
- Stats: export, IT report, 12 sections.
- Payload.
- Settings → Security Analysis, Ads & Trackers, Why am I seeing this ad?, Firewall Rules, Payload Inspector (a second way in), Licenses → License text, plus inline Dual VPN, Diagnostics, Blocklists, Data.

**Proposed tree (4 tabs plus Advanced):**
1. **Home:** status, the switch, level, "Blocked today", alerts entry.
2. **Apps:** list → App (default tab: Connections; Block / Background only at the top; Mismatch and SDKs under "More details").
3. **Activity** (Traffic, renamed): Blocked / All / Live; every blocked row has "Allow this name" (ADR 0010).
4. **Settings:** Protection (level, rules, Allow list), Privacy (notifications, screenshots, language), Data (retention, export, clear), Kill switch and battery, About.
5. **Settings › Advanced** (one row, warning text, everything below kept):
   - Security Analysis
   - HTTPS inspection (the Payload tab moves here)
   - PCAP
   - Dual VPN
   - DNS (DoH, upstream)
   - Certificate Transparency
   - External IP check
   - Cleartext, Weak TLS and QUIC switches
   - Blocklists detail
   - Runtime integrity
   - Network diagnostics
   - Stats (full analytics)
   - Ads & Trackers detail
   - IT report
   - The 11 "Security systems" cards
   - Weekly report

**Nothing is removed.** The only change of reach is that Payload stops being a tab. On a phone managed by an organisation (MDM consent), it could stay a tab.

**Proposed Home:**
- **Stays:** status line, the protection switch (48 dp, labelled), protection level, "Blocked today" with a tap to the list, alerts bell with count, and the Private DNS / battery warnings when they apply.
- **Moves to Activity:** active connections, throughput, the screen-off card, recent alerts.
- **Moves to Advanced:**
  - All 11 security-system cards. Keep one summary card: "1 thing to review →".
  - The encryption health bar.
  - PCAP export.
  - The weekly report figures (reachable from that review card).
- **Removed duplicates:** the second privacy score and the second Settings gear.

**Plain-language names for everyday screens:**

| Now | Proposed |
|---|---|
| Dashboard | Home |
| Protected tunnel running | Protection is on |
| Traffic / Connections | Activity |
| Payload | (moves to Advanced) HTTPS inspection |
| Blocked (24 h) | Blocked today |
| Unencrypted (24 h) | Not encrypted |
| Encryption health | Share of encrypted connections |
| Throughput | Speed now |
| Cleartext | Not encrypted |
| Block Weak TLS | Block outdated encryption |
| Block QUIC for better filtering | Make browsers use filterable connections |
| DNS over HTTPS | Encrypt name lookups |
| Kill switch | Block internet if protection stops |
| App Behavior DNA / Mismatch | App changed its behaviour |
| SDKs | Built-in trackers |
| Hagezi-Pro-mini (etc.) | Ads and trackers list (author in the details) |
| TLS_1_3 · QUIC in rows | hidden; shown in the details |
| Stopped by: DNS / SNI | Blocked by name lookup / Blocked at connection |
| Privacy score letter grade (A/C) | remove; keep one risk word |

## 7. Order of work for 1.1 (each step testable)

1. Fix the false "Not protected" flash: start from `PrivacyVpnService.isRunning` (unit test for the initial state).
2. Fix the export button label and logic (Compose test).
3. Make the action pill distinct from the status pill on Traffic rows (screenshot test).
4. Add "Allow this name" on blocked rows and domain detail, with the existing malware warning (ADR 0010; test from blocked row to rule in 3 taps).
5. Typography: no letter spacing for Arabic, flexible line heights, `PgTextFaint` brought to ≥ 4.5:1, labels on every icon button, 48 dp targets (Compose accessibility checks, plus 200 % font screenshots in 4 languages).
6. Scrollable chips on App detail; App detail opens on Connections (fr/nl/200 % screenshot test).
7. One source for counts and totals (backlog item 2; test that Home, Stats, Ads and Evidence agree).
8. Move the Payload tab and the expert Settings sections under Settings › Advanced (navigation test: every old route still reachable).
9. Slim Home to the proposal above (count elements; task 1 at 0 taps).
10. Replace the polling loops with Room Flows (CPU measurement, backlog items 4 and 10).
11. Rename labels per the table, with strings in 4 languages and review files (§8).
12. Alert severity: a fingerprint change after an app update shouldn't be HIGH (test with an update scenario).
13. Proper splash and app icon.

## 8. Screenshot files

In `C:\Users\ooo77\Documents\CoreLock-ux-audit\` (67 files):

`00_splash_en.png`
`01_home_ar.png`, `01_home_en.png`, `01_home_fr.png`, `01_home_nl.png`
`01b_home_ar_scroll1.png`, `01b_home_en_scroll1.png`, `01c_home_en_scroll2.png`, `01d_home_en_scroll3.png`, `01e_home_en_scroll4.png`
`02_apps_ar.png`, `02_apps_en.png`
`03_appdetail_activity_en.png`, `03_appdetail_ar.png`, `03_appdetail_connections_en.png`, `03_appdetail_en.png`, `03_appdetail_fr.png`, `03_appdetail_nl.png`, `03_appdetail_payload_en.png`, `03_appdetail_sdks_en.png`, `03b_appdetail_en_scroll1.png`
`04_apptraffic_en.png`
`05_domaindetail_en.png`
`06_traffic_ar.png`, `06_traffic_en.png`, `06b_traffic_24h_blocked_en.png`
`07_stats_ar.png`, `07_stats_en.png`, `07b_stats_en_scroll1.png`, `07c_stats_en_scroll2.png`
`08_payload_en.png`, `08b_payload_en_scroll1.png`, `08c_payload_inspection_on_en.png`, `08d_payload_off_again_en.png`
`09_settings_ar.png`, `09_settings_en.png`, `09b_settings_en_scroll1.png`, `09c_settings_en_scroll2.png`, `09d_settings_en_scroll3.png`, `09e_settings_en_scroll4.png`
`10_rules_en.png`
`11_ads_en.png`, `11b_ads_en_scroll1.png`
`12_adprivacy_en.png`, `12b_adprivacy_en_scroll.png`
`13_security_en.png`, `13b_security_en_scroll.png`
`14_licenses_en.png`
`15_language_picker_en.png`
`16_alerts_en.png`
`17_sleepreport_en.png`
`20_home_font200_en.png`, `21_apps_font200_en.png`, `22_traffic_font200_en.png`, `23_settings_font200_en.png`, `24_appdetail_font200_en.png`
`30_task2_off_0s_en.png`, `30_task2_off_2s_en.png`, `30_task2_on_0s_en.png`, `30_task2_on_1s_en.png`
`31_home_coldstart_flash_en.png`
`32_task4_blocked_filter_en.png`, `33_task4_connection_detail_en.png`, `34_task4_add_rule_dialog_en.png`
`35_task8_blocked_evidence_en.png`
`36_task7_background_block_en.png`
`37_task10_export_picker_en.png`

`37_task10_export_picker_en.png` shows Stats in the "CSV ready" state; no file picker appeared.

`31_home_coldstart_flash_en.png` was taken during a run that force-stopped the app, which also restarts the VPN. The proof of the false flash is the two relaunch runs with the service kept running, which I sampled by UI dump; neither run has a screenshot.

## Owner additions

1. **"Something not working?" on Home.** Pick the app (sorted by recent blocks), see what was blocked in plain words, then choose "Allow for this app" or "Allow everywhere". Target: 3 taps, no typing.
2. **"Pause protection for 5 minutes"** as a quick way to test whether CoreLock causes a problem.
3. **Explain protection levels by their trade-off:** Minimal: nothing breaks; Standard: recommended; Strict: some apps may break.
4. **One plain risk word per app**, instead of a letter grade, a risk level and a privacy score together.
5. **Trust first:** consistent numbers across screens and no false "Not protected" flash come before any visual changes.
6. **Export and import of rules and settings.**
