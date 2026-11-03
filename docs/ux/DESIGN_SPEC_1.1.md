# CoreLock 1.1 — Design specification

- **Status:** Accepted by the owner with decisions D1–D12 (2026-11-02, §8)
- **Date:** 2026-11-02
- **Branch:** `redesign-1.1`
- **Input:** `docs/ux/UX_AUDIT_1.1.md` (including "Owner additions")
- **Scope:** UI only. The VPN, packet path, filtering, blocklists, rule logic, database
  schema, inspection and security code stay as they are. Anything that needs a behaviour
  change is listed in §8 and waits for the owner's approval.

---

## Contents

1. Principles
2. Design system (tokens, type, spacing, shape, icons, motion, enforcement)
3. Components
4. Information architecture
5. Parity table (every current screen, card, section and setting → new place)
6. Screen specifications (with loading, empty, error and normal states)
7. Renamed strings (old → new, English)
8. Open decisions for the owner
9. Phase plan and tests

---

## 1. Principles

| Principle | What it means in practice |
|---|---|
| One screen, one job | Home answers "am I protected?"; Activity answers "what was stopped?"; Apps answers "what is this app doing?"; Settings changes things. |
| Everyday first, expert on request | Every expert feature still exists, but behind **Settings › Advanced** or a collapsed **Technical details** block. |
| Status before data | The first thing on every screen is a status in words; numbers and lists come after. |
| Actions where the problem is | Allow, Block and "Remove my rule" sit on the blocked item itself, not in a separate Rules screen. |
| Plain language | Written for a smart 14-year-old. Banned words on everyday screens: §7.1. |
| Honest | No false "Not protected", no promise we can't keep, limits said where they matter. |
| Calm colours | Teal = protected. Amber = needs attention. Red = real danger only (malware, phishing, protection stopped unexpectedly). |
| Fewer, bigger, clearer | Home goes from ~48 elements over 5 screens to ≤ 8 elements on one screen. |

---

## 2. Design system

### 2.1 Colour tokens

The theme moves from dark-only (`PrivacyGuardTheme(darkTheme = true)`, `Theme.kt`) to
light + dark + follow-system. Neutrals are deep ink/navy, with a single teal-green accent
(owner's preference). Tokens are semantic: code uses `CoreLockTheme.colors.protected`,
never a hex value.

| Token | Dark | Light | Use |
|---|---|---|---|
| `background` | `#0E1320` | `#F6F8FB` | Screen background |
| `surface` | `#161D2E` | `#FFFFFF` | Cards, sheets, rows |
| `surfaceVariant` | `#1F2840` | `#EDF1F6` | Segmented track, chips, skeletons, input fields |
| `onSurface` | `#ECF0F7` | `#111827` | Main text |
| `onSurfaceMuted` | `#A9B3C6` | `#4B5567` | Subtitles, secondary text, inactive tab labels |
| `primary` | `#3DDBB4` | `#00796B` | Primary buttons, switch on, selected tab, links |
| `onPrimary` | `#04211A` | `#FFFFFF` | Text/icons on `primary` |
| `protected` | `#3DDBB4` | `#00796B` | "You're protected" status (same hue as primary on purpose: one accent) |
| `protectedContainer` / `onProtectedContainer` | `#0F3A33` / `#A7F3DD` | `#D3F5EC` / `#00382F` | Hero card when protected |
| `attention` | `#F2B85B` | `#8A5A00` | Needs attention (Private DNS, Usage Access, battery) |
| `attentionContainer` / `onAttentionContainer` | `#3D2E0F` / `#FFE1A8` | `#FCEBC7` / `#4A3000` | Attention banner, hero when off |
| `danger` | `#FF8A8A` | `#B3261E` | Real danger: malware/phishing, destructive buttons |
| `dangerContainer` / `onDangerContainer` | `#4A1A1F` / `#FFD6D6` | `#FBDCD9` / `#5C0F0B` | Malware warning sheet, "Clear all data" confirmation |
| `outline` | `#8390A8` | `#79849A` | Borders, dividers, switch track off, focus ring (non-text only) |

#### Contrast ratios (WCAG 2.x formula, computed 2026-11-02)

Requirement: text ≥ 4.5:1; large text, icons and UI boundaries ≥ 3:1.

| Pair | Dark | Light | Passes |
|---|---|---|---|
| onSurface on background | 16.22 | 16.67 | text |
| onSurface on surface | 14.71 | 17.74 | text |
| onSurface on surfaceVariant | 12.80 | 15.64 | text |
| onSurfaceMuted on background | 8.78 | 7.06 | text |
| onSurfaceMuted on surface | 7.96 | 7.51 | text |
| onSurfaceMuted on surfaceVariant | 6.93 | 6.63 | text |
| primary / protected on background | 10.59 | 5.00 | text |
| primary / protected on surface | 9.60 | 5.32 | text |
| primary / protected on surfaceVariant | 8.35 | 4.69 | text |
| onPrimary on primary | 9.69 | 5.32 | text |
| onProtectedContainer on protectedContainer | 9.86 | 11.23 | text |
| protected on protectedContainer | 7.17 | 4.57 | text |
| attention on surface | 9.43 | 5.93 | text |
| attention on surfaceVariant | 8.21 | 5.23 | text |
| onAttentionContainer on attentionContainer | 10.39 | 10.41 | text |
| danger on surface | 7.41 | 6.54 | text |
| danger on surfaceVariant | 6.45 | 5.76 | text |
| onDangerContainer on dangerContainer | 10.86 | 10.69 | text |
| outline on background | 5.76 | 3.54 | non-text |
| outline on surface | 5.22 | 3.76 | non-text |
| outline on surfaceVariant | 4.54 | 3.32 | non-text |

Light `outline` is for borders and icons only, never for text. The weakest text pair is
light `protected` on `protectedContainer` (4.57:1); it passes, but the hero title uses
`onProtectedContainer` (11.23:1) and `protected` is used only for the status icon there.

For comparison, today's `PgTextFaint` `#5A6478` is 2.54–3.27:1 and is used 65 times,
including inactive tab labels (audit finding "Faint text fails contrast"). It is removed.

**Nothing by colour alone.** Every status has an icon and a word as well: protected =
shield-check + "You're protected", attention = info/warning + text, danger = error icon +
text. Blocked vs allowed rows differ by icon and text, not only colour.

### 2.2 Typography

- System font: Roboto (Latin), Noto Sans Arabic (Arabic). Nothing bundled.
- **No monospace and no letter spacing** in UI labels. Today `labelSmall` is Monospace
  10 sp with 1.2 sp letter spacing and `labelMedium` Monospace 11 sp (`Type.kt`); both go.
  Monospace stays only inside Technical details for values like IPs and hashes
  (`technicalValue` style), never for labels.
- Line heights are not fixed in a way that clips: every style uses `lineHeight` in `em`
  relative to its size (≈ 1.3–1.4), so 200 % font and Arabic glyphs never overlap or cut.
- Text never has a fixed height container; rows grow.

| Role (Material 3) | Size / weight | Use |
|---|---|---|
| `headlineMedium` | 28 sp / 500 | Hero status ("You're protected") |
| `titleLarge` | 22 sp / 500 | Screen titles |
| `titleMedium` | 16 sp / 500 | Card and row titles, sheet titles |
| `bodyLarge` | 16 sp / 400 | Main body text |
| `bodyMedium` | 14 sp / 400 | Subtitles, row secondary text |
| `labelLarge` | 14 sp / 500 | Buttons, segmented control, tabs |
| `labelMedium` | 12 sp / 500 | Chips, small badges, section headers |
| `technicalValue` | 14 sp / 400, monospace | Only in Technical details (IP, hash, fingerprint) |

Nothing smaller than 12 sp. Section headers are sentence case, not ALL CAPS.

### 2.3 Spacing

4 dp grid, only these values: **4, 8, 12, 16, 24, 32, 48**.

- Screen side padding 16; between cards 12; inside a card 16; between sections 24.
- Minimum touch target **48 × 48 dp** for everything tappable, including icon buttons
  and the rule delete button (today 32 dp, `RulesScreen.kt:231`).

### 2.4 Shape

| Token | Radius | Use |
|---|---|---|
| `small` | 12 dp | Chips, buttons, text fields, segmented control |
| `card` | 16 dp | Cards, rows grouped in a card, banners |
| `sheet` | 28 dp (top corners) | Bottom sheets, dialogs |

### 2.5 Icons

- One family: Material Symbols **Rounded**, from `material-icons-extended`
  (already a dependency; no new dependency).
- Every meaningful icon has a `contentDescription` (string resource). Decorative icons
  next to a text label use `null`.
- Directional icons (chevron, back, arrow) are auto-mirrored in RTL.
- Fixes audit findings on unlabelled `IconButton`s (`MitmScreen.kt:438-439`, `:460-461`).

### 2.6 Motion

- 150 ms for small state changes (switch, chip), 200 ms for content changes, 300 ms for
  sheets and screen transitions. Standard easing.
- When Android's "Remove animations" is on (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`
  or `TRANSITION_ANIMATION_SCALE == 0`), all durations are 0. One helper
  (`CoreLockMotion`) reads it, so no screen has its own durations.
- No looping animations, no pulsing on Home (battery and distraction).

### 2.7 Enforcement

- The existing theme package `com.privacyguard.app.ui.theme` exposes
  `CoreLockTheme.colors`, `.spacing`, `.shapes` and the Material 3 `Typography`.
- A JVM unit test (`ThemeTokenUsageTest`) scans every UI source file
  (`app/src/*/java/com/privacyguard/**/ui/**`)
  and fails on `Color(0x…)`, `Color.Red`-style literals and `fontSize = N.sp` outside the
  theme package. It starts with an allow-list of today's files and the list must shrink
  every phase; it is empty at the end of Phase 8. (A test, not a custom lint rule: no new
  dependency, and it runs in the normal unit-test task.)
- A second test (`ContrastTokensTest`) recomputes every pair in §2.1 and fails below
  4.5:1 for text pairs and 3:1 for non-text pairs, so a future token edit can't break
  contrast silently.

---

## 3. Components

All in `com.privacyguard.app.ui.components` (new files next to the existing ones). Each has `@Preview`s in **light, dark, RTL
(Arabic) and 200 % font** (`fontScale = 2f`). Phase 2 also adds a debug-only catalog
screen (in `app/src/debug/`, never in the release manifest) that shows every component
in every state, used for screenshots.

| Component | Purpose | Key rules |
|---|---|---|
| `StatusHeroCard` | Home status: protected / off / starting / stopped unexpectedly | Whole card tappable (toggles protection); one `Switch` ≥ 48 dp labelled "Protection"; TalkBack reads "Protection, on. You're protected. Blocking ads and trackers, Standard." |
| `PrimaryToggleRow` | A row whose main job is a switch | Whole row toggles; `Modifier.toggleable` with `Role.Switch`; title + one-line subtitle |
| `SettingRow` | A row that opens something or shows a value | Title ≤ ~5 words, subtitle one line, optional value text, trailing chevron; `Role.Button` |
| `SectionHeader` | Groups rows | Sentence case, `labelMedium`, `onSurfaceMuted`, marked `heading()` for TalkBack |
| `InfoSheet` | "Learn more" content | Bottom sheet, title + body + one close button; long explanations live here, not in subtitles |
| `AppRow` | App in a list | Icon, name, status in words, "N blocked today", chevron. No package name, no grade, no inline switch |
| `ActivityRow` | One blocked or allowed connection | App icon, friendly destination, plain reason; blocked = block icon + "Blocked · Ads list", allowed = check icon. Not a button-looking pill |
| `PrimaryButton`, `SecondaryButton`, `DestructiveButton` | Actions | Verbs, sentence case, ≥ 48 dp; destructive uses `danger`, always with a confirmation |
| `SegmentedControl` | 2–4 mutually exclusive choices | Selected = filled `primary` + check icon (not colour only); side by side while every label's longest word fits, otherwise stacked vertically (never a word broken in the middle, e.g. Dutch at 200 %); `Role.RadioButton` per segment |
| `CoreLockBottomSheet` | Detail sheets, level picker | Drag handle, 28 dp top corners, focus moves to the title on open, back closes |
| `EmptyState` | Nothing to show | Icon, one sentence, optional action ("No apps blocked yet") |
| `ErrorState` | Load failed | Icon, plain sentence, "Try again" button. Never a raw exception |
| `LoadingSkeleton` | First load | Shapes the size of the real rows; no spinner on Home for the status (status reads the service state immediately) |
| `AttentionBanner` | At most one per screen | `attentionContainer`, icon, one sentence, one action verb ("Open settings"); dismissible only when the problem is optional |
| `ChipRow` | Horizontally scrolling chips | Scrolls instead of squeezing (fixes "chips squeezed in fr/nl/200 %"), each chip ≥ 48 dp tall touch area |
| `TechnicalDetails` | Collapsed expert data | Collapsed by default; header "Technical details" + chevron; `technicalValue` inside; allowed to use technical words |

---

## 4. Information architecture

### 4.1 Tabs (bottom navigation)

| Tab | Icon (Rounded) | Job |
|---|---|---|
| **Home** | `Shield` | Am I protected? Turn it on/off, change level, fix a broken app |
| **Apps** | `Apps` | What each app does; block an app |
| **Activity** | `ListAlt` | What was blocked, why, and allow it |
| **Settings** | `Settings` | Everything else; expert features under Advanced |

Removed from the tab bar: **Traffic** (becomes Activity), **Stats** (moves to Advanced ›
Analysis › Statistics) and **Payload** (moves to Advanced › HTTPS inspection). Nothing is
deleted.

Inactive tab labels use `onSurfaceMuted` (7.96:1 dark) instead of `PgTextFaint`.

### 4.2 Tree

```
Home
├─ Status hero (switch)
├─ Level row ─────────────► Level sheet (Minimal / Standard / Strict)
├─ "Blocked today: N" ────► Activity (Blocked)
├─ "Something not working?" ► Fix flow (3 steps)
└─ ≤ 1 attention card (Private DNS / Usage Access / battery / alerts / …)

Apps
├─ Search + All / Blocked / Active now
└─ App detail
   ├─ Allowed / Blocked in background / Blocked
   ├─ Connected recently (by organisation and purpose) — Allow / Block
   ├─ Built-in trackers
   └─ Technical details (package, Mismatch, Behaviour, permissions, payload, raw connections)

Activity
├─ Blocked / All, grouped Just now / Today / Yesterday
├─ Row ► Detail sheet (What / Why / Allow for this app / Allow everywhere / Keep blocked / Remove my rule / Technical details)
└─ "Something not working?" ► Fix flow

Settings
├─ Protection
│  ├─ Protection level
│  ├─ Rules and allowed sites ► Rules
│  ├─ Blocklists ► Blocklists (simple)
│  ├─ Kill switch (status) ► Open Android settings
│  └─ Restart after a crash
├─ Notifications (on, show details)
├─ Language
├─ Appearance (System / Light / Dark)          ← needs decision D4
├─ Data (keep history for, export history, clear all data, screenshots, weekly report)
├─ About (version, what CoreLock can and can't do, blocking policy, licenses, releases page, report a problem)
└─ Advanced  "For experts. Wrong settings can break your connection."
   ├─ Network: Encrypted DNS, DNS server, Use two proxy servers, Fast web protocol (QUIC), Old encryption, Unencrypted connections, Public IP check
   ├─ HTTPS inspection: (today's Payload tab, whole)
   ├─ Analysis: Security analysis, Statistics, IT report, Certificate checks, App fingerprints, Packet capture, Weekly report, Alerts, Security systems, Ad privacy, Ads & trackers detail, Night report, Evidence
   └─ Diagnostics: Network diagnostics, App integrity, Blocklist details
```

### 4.3 Deep links that must keep working

Notifications open the app with `pg_nav_route` (`NotificationHelper.kt:292, 331, 369,
398, 428, 516`; read at `MainActivity.kt:42`, handled at `AppNavHost.kt:115-117`).

| Old route | Used by | New destination |
|---|---|---|
| `alerts` | alert notifications (`:292`, `:369`, `:428`) | Advanced › Analysis › Alerts (same screen) |
| `crypto` | TLS / fingerprint notification (`:331`) | Advanced › Analysis › Security analysis |
| `connections` | blocked-connection notification (`:398`) | Activity tab, Blocked |
| `apps` | app notification (`:516`) | Apps tab |

The old route strings are kept as aliases in the nav graph, so a notification posted by
1.0 still opens the right place after the update. A navigation parity test (§9) checks
every alias.

The Quick Settings tile (`ic_tile_vpn`) keeps working unchanged; it is not part of the UI
redesign beyond the icon refresh in Phase 7.

---

## 5. Parity table

Every current screen, card, section and setting, and where it lives in 1.1. "Same
screen" means the existing composable is reused under a new entry point (possibly
restyled with the new tokens). Nothing is removed.

### 5.1 Routes (`AppNavHost.kt`)

| # | Old route / place | 1.1 place | Phase |
|---|---|---|---|
| R1 | `onboarding` | Onboarding (≤ 3 screens) | 7 |
| R2 | `dashboard` (tab "Home") | Home tab (new) | 3 |
| R3 | `apps` (tab "Apps") | Apps tab (new list) | 5 |
| R4 | `appDetail/{packageName}?appName=` | App detail (new everyday view; old tabs under Technical details) | 5 |
| R5 | `appTraffic/{packageName}?from&to&idle` | App detail › Connected recently; old screen kept under Technical details › Raw connections | 5 |
| R6 | `connections` (tab "Traffic") | Activity tab (new); Live speed and throughput → Advanced › Analysis › Statistics | 4 |
| R7 | `domain/{host}` | Activity detail sheet › Technical details › "More about this name" (same screen, now with Allow/Block actions) | 4 |
| R8 | `statistics` (tab "Stats") | Advanced › Analysis › Statistics | 6 |
| R9 | `payloads` (tab "Payload") | Advanced › HTTPS inspection | 6 |
| R10 | `settings` (tab "Settings") | Settings tab (new sections) | 6 |
| R11 | `alerts` | Advanced › Analysis › Alerts; Home attention card when there is a new high alert | 3, 6 |
| R12 | `evidence/{kind}?at=` | Advanced › Analysis › Security systems › each card (same screen) | 6 |
| R13 | `sleepReport?at=` | Advanced › Analysis › Night report (same screen) | 6 |
| R14 | `ads` | Settings › Protection › Blocklists (simple) + Advanced › Analysis › Ads & trackers detail | 6 |
| R15 | `adPrivacy` | Advanced › Analysis › Ad privacy (same screen) | 6 |
| R16 | `rules` | Settings › Protection › Rules and allowed sites | 6 |
| R17 | `crypto` | Advanced › Analysis › Security analysis | 6 |
| R18 | `licenses` | Settings › About › Open-source licenses | 6 |
| R19 | `licenseText/{key}` | Same, from the licenses list | 6 |
| R20 | — (new, D12) | Home › one-time "What's new: some things moved" card after updating from 1.0, with links to Statistics and HTTPS inspection | 3 |

### 5.2 Home cards (`DashboardScreen.kt`)

| # | Old element | 1.1 place |
|---|---|---|
| H1 | "Protected / Not protected" + `PrivacySwitch` (~30 dp) | Home › Status hero, switch ≥ 48 dp |
| H2 | Protection level | Home › Level row + level sheet; also Settings › Protection |
| H3 | "Privacy score N" | Advanced › Analysis › Statistics (score factors and share score are already there) |
| H4 | "Encryption health" | Advanced › Analysis › Statistics › Encryption |
| H5 | "Blocked (24 h)" | Home › "Blocked today: N" (single source, §6.1) |
| H6 | "Throughput" | Advanced › Analysis › Statistics |
| H7 | "Security systems" (cards BEHAVIOR, IPV6, KILL_SWITCH, NO_DECRYPTION, ENTROPY, NIGHT, BEACON, UPLOAD, TRUST, BACKGROUND, CLEARTEXT, DNS) | Advanced › Analysis › Security systems (all 12, each opens its evidence screen). KILL_SWITCH also at Settings › Protection › Kill switch. A card that needs the user's action can raise the one Home attention card |
| H8 | "Recent alerts" | Advanced › Analysis › Alerts; newest high alert can be the Home attention card |
| H9 | "Recommendations" | Home attention card (one, highest priority); full list in Advanced › Analysis › Security systems |
| H10 | Private DNS warning | Home attention card (priority 1) + before starting protection (unchanged) |
| H11 | Kill switch card button (`:565-571`) | Settings › Protection › Kill switch › "Open Android settings" |
| H12 | — (new, D12) | Home › "What's new: some things moved" card (one time after the 1.1 update) |

Attention card priority (only the first that applies is shown): 1 Private DNS in hostname
mode → 2 protection stopped unexpectedly → 3 malware blocked in the last hour → 4 Usage
Access missing while a background rule exists → 5 battery (see below) → 6 new high
alert → 7 "What's new: some things moved" (D12, one time) → 8 a recommendation.

Battery card (changed after the Phase 3 screenshots, 2026-11-03): shown only when
background use is **Restricted** (always, can't be dismissed), or when battery use is
**Optimized** and protection was really stopped by the system: it stopped unexpectedly with
no reason the app recorded, and Android's exit record (`ApplicationExitInfo`, Android 11+)
names a system kill (`SystemStopCheck`). Never for "Optimized" alone: that is Android's
default, so the card showed on every fresh install. General battery advice moves into
onboarding (Phase 7, §6.7 step 3).

### 5.3 Statistics sections (`StatisticsScreen.kt`) → Advanced › Analysis › Statistics

All sections stay on that screen: totals, block rate, encryption, score factors, share
score, IT report (JSON + PDF), CSV export, network map, by organisation, heatmap, threat
timeline, DNS anomalies, top countries, networks, 7-day trend, top apps.
**IT report** is also listed as its own row under Analysis; **CSV export** also under
Settings › Data › Export history. Live speed moves here from Traffic.

### 5.4 Traffic (`ConnectionsScreen.kt`)

| Old | 1.1 |
|---|---|
| Live / Last 24 h | Activity groups (Just now / Today / Yesterday); live speed → Statistics |
| All / Blocked / Cleartext filter | Blocked / All segmented control; Cleartext → Advanced › Network › Unencrypted connections (shows that list) |
| Detail sheet: "Stopped by", View app, Block | Activity detail sheet: What, Why, actions, Technical details (Stopped by, IP, port, protocol, time) |

### 5.5 Apps and App detail

| Old | 1.1 |
|---|---|
| Filters All / High risk / Blocked | All / Blocked / Active now. "High risk" becomes the plain risk word in each row ("Mostly trackers", "Active in the background") and the sort option "Most blocked". See D9 |
| Sort Risk / Data / Destinations / Background | Overflow menu "Sort by" on Apps: Most blocked (default), Name, Most data, Background activity |
| Per-row switch | Removed from the row; blocking is in App detail (fixes "unlabelled switch") |
| Letter grade | App detail › Technical details › Risk grade |
| Package name in the row | App detail › Technical details |
| Tabs Mismatch / Connections / SDKs / Activity / Payload | Connections → Connected recently; SDKs → Built-in trackers; Mismatch, Activity (behaviour), Payload → Technical details |
| Block button | Segmented control: Blocked |
| Background switch + Usage Access warning | Segmented control: Blocked in background + Usage Access step shown when chosen |
| Share report | App detail overflow menu › Share report |

### 5.6 Settings (`SettingsScreen.kt`)

| # | Old key / row | 1.1 place |
|---|---|---|
| S1 | `set_protection_level` | Settings › Protection › Protection level (opens the same level sheet as Home) |
| S2 | `set_block_cleartext` | Advanced › Network › Unencrypted connections |
| S3 | `set_block_weak_tls` | Advanced › Network › Old encryption |
| S4 | `set_block_quic` | Advanced › Network › Fast web protocol (QUIC) |
| S5 | `set_kill_switch` (status row, `:247-260`) | Settings › Protection › Kill switch (status + "Open Android settings") |
| S6 | `set_auto_restart` + dialog | Settings › Protection › Restart after a crash |
| S7 | `set_notifications` | Settings › Notifications › Notifications |
| S8 | `set_notification_details` | Settings › Notifications › Show details |
| S9 | `set_allow_screenshots` | Settings › Data › Allow screenshots |
| S10 | `set_weekly_report` | Settings › Notifications › Weekly summary (and listed in Advanced › Analysis) |
| S11 | `set_doh` + `set_doh_provider` | Advanced › Network › Encrypted DNS |
| S12 | `set_ct` | Advanced › Analysis › Certificate checks |
| S13 | `set_external_ip` | Advanced › Network › Public IP check |
| S14 | `set_always_on_hint`, `set_open_vpn_settings` | Settings › Protection › Kill switch |
| S15 | `set_open_battery_settings` | Advanced › Diagnostics (battery check) + Home attention card when relevant |
| S16 | `set_runtime_integrity` (posture, signature, signing mode, root, debugger, emulator, keystore, hardening) | Advanced › Diagnostics › App integrity |
| S17 | `set_upstream_dns` / `set_resolver` / `set_custom` | Advanced › Network › DNS server |
| S18 | `set_diagnostics` / `set_run_checks` (~42 `diag_*` checks) | Advanced › Diagnostics › Network diagnostics (unchanged list) |
| S19 | `set_blocklists` (Refresh now, run report) | Settings › Protection › Blocklists (simple) + Advanced › Diagnostics › Blocklist details |
| S20 | `set_data_management` › `set_retention` (7/14/30/90) | Settings › Data › Keep history for |
| S21 | `set_clear_data` / `set_danger_zone` | Settings › Data › Clear all data (destructive button + confirmation) |
| S22 | `set_nav_security` | Advanced › Analysis › Security analysis |
| S23 | `set_nav_ads` | Settings › Protection › Blocklists + Advanced › Analysis › Ads & trackers detail |
| S24 | "Why am I seeing this ad?" | Advanced › Analysis › Ad privacy |
| S25 | `set_nav_rules` | Settings › Protection › Rules and allowed sites |
| S26 | `set_nav_payloads` | Advanced › HTTPS inspection |
| S27 | `set_dual_vpn` / `set_dual_enable` / hop 1 and 2 host, port, username, password | Advanced › Network › Use two proxy servers |
| S28 | `set_dual_block_udp` | Advanced › Network › Use two proxy servers › Block other traffic outside the chain |
| S29 | `set_about` › version | Settings › About › Version |
| S30 | "Check for updates" (opens GitHub releases) | Settings › About › See new versions |
| S31 | Open-source licenses | Settings › About › Open-source licenses |
| S32 | Language picker (`LanguagePicker.kt`) | Settings › Language |
| S33 | — (new) | Settings › Appearance (D4) |
| S34 | — (new) | Settings › About › What CoreLock can and can't do (InfoSheet; text from `docs/BLOCKING_POLICY.md`) |
| S35 | — (new, D11) | Settings › About › Report a problem (opens the browser on the GitHub issue templates, `…/CoreLock/issues/new/choose`) |

### 5.7 HTTPS inspection (`MitmScreen.kt`, enterprise) → Advanced › HTTPS inspection

Moved whole, restyled with tokens only: consent dialog (`:179-180`), ScreenLockRequired
dialog, SettingsInstall dialog, CaInstallCard (install, regenerate, remove old, save
.crt), QuicBlockRow, CaptureListScreen, filters (All, POST, GET, Flagged, Has body),
PayloadListItem, AnalyticsScreen, DetailScreen (Payload / Headers / Risk / Fields).
The two unlabelled icon buttons get content descriptions. Per-app payload stays in App
detail › Technical details. The extra confirmation on every switch-on is decision D5.

### 5.8 Other screens

| Old | 1.1 |
|---|---|
| `AlertInboxScreen` (All / Critical / High / Medium) | Advanced › Analysis › Alerts (same filters, restyled) |
| `SecurityAnalysisScreen` ("Crypto analysis": JA3/JA4, known-bad JA3, ciphers, CT monitor, Dual VPN hop diagram) | Advanced › Analysis › Security analysis; the hop diagram also inside "Use two proxy servers" |
| `AdsScreen` (categories, custom rules, blocklists on, refresh) | Blocklists (simple) shows lists + last update + Refresh; categories and custom rules in Ads & trackers detail |
| `AdPrivacyScreen` (mic/camera timeline, Usage Access, location audit, revoke mic, "Block ad and attribution SDKs", never-blocked list) | Advanced › Analysis › Ad privacy (same) |
| `RulesScreen` (suggested, active, add, delete) | Rules and allowed sites (restyled, 48 dp delete) |
| `OpenSourceLicensesScreen` | About › Open-source licenses |
| `EvidenceScreen`, `SleepReportScreen` | Under Security systems / Night report |
| Notifications (tunnel state, blocked, alerts, weekly) | Unchanged behaviour; tap targets per §4.3 |
| Quick Settings tile | Unchanged |

---

## 6. Screen specifications

States for every screen: **Loading** (skeleton), **Empty**, **Error** (plain sentence +
"Try again"), **Normal**. Data comes from Room `Flow`s / `StateFlow`, not polling.

### 6.1 Home

**Normal (protected)**

```
┌──────────────────────────────────────┐
│ [shield✓]  You're protected     [●━] │  ← whole card toggles; switch ≥ 48 dp
│ Blocking ads and trackers · Standard │
└──────────────────────────────────────┘
  Protection level                 ›
  Standard · Blocks ads and trackers. Recommended.

  Blocked today                   128 ›   ← opens Activity › Blocked

  [ Something not working? ]              ← secondary button

  ┌ attention card (at most one) ───────┐
  │ ⚠ Private DNS is on. Apps can skip │
  │   CoreLock.        [Open settings] │
  └─────────────────────────────────────┘
```

- Hero states:
  - **On:** "You're protected" + subtitle "Blocking ads and trackers · Standard" (Minimal:
    "Blocking malware · Minimal"; Strict: "Blocking ads, trackers and more · Strict").
    `protectedContainer`.
  - **Off:** "Protection is off" / "Turn it on to block ads, trackers and malware."
    `attentionContainer` (amber, not red: the user chose it).
  - **Starting:** "Starting…" with the switch disabled. Shown only while the service is
    actually starting.
  - **Stopped unexpectedly** (revoked by another VPN, crash): "Protection stopped" /
    "Another VPN may have taken over." `dangerContainer` + "Turn on".
- **No false "Not protected" flash:** the hero reads the real service state synchronously
  on first composition (the running flag the service already publishes), not after the
  slow refresh (`DashboardViewModel.kt:34`, `:134-139`, `:270`). Until the state is known
  the hero shows a skeleton, never "off". Test: state is known at the first frame.
- "Blocked today: N" counts since local midnight from `BlockStats(db).between(midnight,
  now)`, the same source Activity and Statistics use (single source of truth; test that
  all three agree).
- Level row opens the **Level sheet**: three radio rows, trade-off first:
  - Minimal — "Blocks malware. Nothing breaks."
  - Standard — "Blocks ads and trackers. Recommended."
  - Strict — "Blocks more. Some apps or sites may break."
  Tapping one applies it and closes the sheet (2 taps from Home).
- **Loading:** hero from service state (instant); count and level row as skeletons.
- **Empty:** "Blocked today: 0" with no chevron while protection is off.
- **Error:** count failed → row shows "Couldn't load" + retry icon (labelled). The hero
  never depends on the database.
- Removed from Home (moved, see §5.2): score, encryption health, throughput, security
  systems, recent alerts list, recommendations list.
- **"What's new: some things moved"** (D12): shown once, only to users who updated from
  1.0 (not on a fresh install), in the attention-card slot (priority 7, so a real problem
  still wins). Text: "Some things moved. Statistics and HTTPS inspection are now in
  Settings › Advanced." Buttons: "Open Statistics", "Open HTTPS inspection", and a close
  button labelled "Dismiss". Dismissing or opening either link hides it for good. Stored
  as one UI-only preference flag (no database change). Update vs fresh install is decided
  from existing data (onboarding already completed before 1.1 first ran); Phase 3
  confirms the exact signal.

### 6.2 Activity

- Top: segmented control **Blocked / All** (default Blocked). Groups: Just now (last
  5 min), Today, Yesterday, Earlier (until retention).
- `ActivityRow`: app icon, friendly name ("Google ads", from the existing organisation
  lookup; the host when unknown), reason in words ("Blocked · Ads and trackers list",
  "Blocked · Your rule", "Blocked · Malware list", "Allowed").
- Header button: "Something not working?" (same flow as Home).
- **Detail sheet** (tap a row):
  - What: "Google ads (googleads.g.doubleclick.net) from Chrome". The host is shown here
    because the user needs it to decide.
  - Why: "It's on the Ads and trackers list." / "You blocked it." / "It's on the malware
    list."
  - Actions:
    - Ads/tracker list block at the connection layer (`BlockSource` SNI or QUIC):
      **Allow for this app**, **Allow everywhere**, **Keep blocked**.
    - Ads/tracker list block at the DNS layer (a `dns_queries` row): **Allow
      everywhere** and **Keep blocked** only, with the line "This was blocked before
      the app connected, so it can only be allowed for all apps." (D3)
    - Malware list: **Keep blocked** is primary; "Allow anyway" shows the existing
      malware warning first and creates the rule with `overridesSecurity` only after
      confirmation (same path as today's rule dialog).
    - The user's own rule: **Remove my rule** (ADR 0010).
    - Allowed row: **Block for this app**, **Block everywhere**.
  - All rules created here use `HIGH_PRIORITY` (CLAUDE.md §5.3) through the existing rule
    repository; no new rule logic.
  - Technical details (collapsed): Stopped by (DNS / SNI / QUIC / DoH / DoT, shown with
    those words here), IP, port, protocol, time, "More about this name" (old domain
    screen).
- **Fix flow — "Something not working?"** (3 taps, no typing):
  1. "Which app isn't working?" — apps with blocks in the last hour, most recent first;
     "Other app" opens the full list.
  2. "This was blocked in the last hour" — names for that app, most likely cause first
     (most attempts, then most recent; known-breakage lists like CDNs first).
  3. "Allow for this app" / "Allow everywhere" → snackbar "Allowed. Try the app again."
     with **Undo** (deletes the rule just created). For DNS-layer blocks only "Allow
     everywhere" is offered, with the D3 explanation.
  - Later (D1, separate change after Phase 4): "Pause protection for 5 minutes". Not part
    of Phase 4; the fix flow leaves room for one more button.
- **Loading:** 6 skeleton rows. **Empty (Blocked):** "Nothing blocked yet. CoreLock will
  show blocked ads and trackers here." **Empty while off:** "Protection is off, so nothing
  is being blocked." + "Turn on". **Error:** "Couldn't load activity." + Try again.

### 6.3 Apps

- Search field (by app name), segmented control **All / Blocked / Active now** ("Active
  now" = traffic in the last 5 minutes).
- `AppRow`: icon, name, status in words ("Allowed", "Blocked", "Blocked in background"),
  the plain risk word (§6.3.1), "N trackers blocked today", chevron. Sorted by most
  blocked today; overflow "Sort by".

#### 6.3.1 Plain risk word (D9)

Computed only from fields `AppsViewModel` already produces (`AppRiskItem`, last 24 h).
The first rule that matches wins. Tapping the word opens an `InfoSheet` with the
explanation.

| # | Condition (existing data) | Word | Colour | Explanation when tapped |
|---|---|---|---|---|
| 1 | `stalkerwareScore >= 70` (same threshold as today's stalkerware badge, `AppsScreen.kt:139`) | Could watch you | danger + icon | "This app has permissions and behaviour often seen in apps that track a person: <reasons>. If you didn't install it yourself, check it." (`stalkerwareReasons`) |
| 2 | `maxRiskScore >= 70` (today's "HIGH", `AppsViewModel.kt:47`) | High risk | attention | "CoreLock saw risky connections from this app in the last 24 hours. Open the app to see them." |
| 3 | `cleartextCount > 0` | Sends unprotected data | attention | "Some of this app's connections weren't encrypted, so others on the network could read them." |
| 4 | `suspiciousCount > 0` (connections blocked in the window) | Uses trackers | muted | "CoreLock blocked tracker or ad connections from this app. That's normal for many apps." |
| 5 | `backgroundCount > 0` | Active in the background | muted | "This app used the internet while you weren't using it." |
| 6 | `totalDestinations == 0` | No activity | muted | "This app didn't use the internet in the last 24 hours." |
| 7 | otherwise | Looks normal | muted | "Nothing unusual in the last 24 hours." |

The word is always text (never colour alone). The letter grade (`privacyGrade`) moves to
Technical details unchanged. A unit test covers every row of this table.
- **Loading:** skeleton rows. **Empty search:** "No app called '<q>'." **Empty Blocked:**
  "No apps blocked. Open an app to block it." **Error:** plain + Try again.

### 6.4 App detail

- Header: icon, name, status sentence ("Allowed. 12 trackers blocked today.").
- Segmented control **Allowed / Blocked in background / Blocked**, with one line under it:
  - Blocked in background: "Can use the internet only while you're using it."
  - If Usage Access is missing when this is chosen: an `AttentionBanner` "CoreLock needs
    Usage Access to know when an app is in the background." + "Open settings". The rule is
    still saved (today's behaviour).
  - Blocked: "Can't use the internet."
- Connected recently: grouped by organisation and purpose (existing purpose grouping from
  `AppTrafficScreen`), newest first; each group row has Allow / Block (app-specific rule,
  see D8).
- Built-in trackers: today's SDKs tab content in plain words.
- Technical details (collapsed): package name, risk grade, Mismatch, Behaviour data,
  permissions, payload (enterprise), raw connections (old `appTraffic` screen).
- Overflow: Share report.
- Default view is the everyday one; Mismatch is never the first thing shown.
- States: loading skeleton; empty "No connections in the last 24 hours."; error plain.

### 6.5 Settings

Each row: title ≤ ~5 words + one-line subtitle; longer text in a Learn more sheet.

| Section | Rows (title — subtitle) |
|---|---|
| Protection | Protection level — "Standard · Recommended" · Rules and allowed sites — "Sites and apps you allowed or blocked" · Blocklists — "Updated 2 days ago" · Kill switch — "On: no internet without CoreLock" / "Off" + "Open Android settings" · Restart after a crash — "Turn protection back on by itself" |
| Notifications | Notifications — "Status and important warnings" · Show details — "Show app names in notifications" · Weekly summary — "A short report every week" |
| Language | Language — "System default" |
| Appearance | Theme — "System / Light / Dark" (D4) |
| Data | Keep history for — "30 days" · Export history — "Save a file you can open in a spreadsheet" · Allow screenshots — "Off protects your history" · Clear all data — "Deletes history and statistics. Rules stay." (destructive, confirm) |
| About | Version — "1.1.0" · What CoreLock can and can't do · Blocking policy · Open-source licenses · See new versions — "Opens the GitHub releases page" · Report a problem |
| — | **Advanced** — "For experts. Wrong settings can break your connection." |

(The "Clear all data" subtitle must match what `clearData` actually deletes; Phase 6
checks the code and words it exactly.)

### 6.6 Advanced

One entry screen with four rows, each opening one screen. Here technical words are
allowed in subtitles and Technical details, but every title stays plain.

- **Network:** Encrypted DNS — "Hide which sites you look up from your network" (+
  provider) · DNS server — "Which server answers lookups" · Use two proxy servers — "Not
  encrypted. The first server can see where you connect." · Fast web protocol (QUIC) —
  "Block it so CoreLock can see more connections" · Old encryption — "Block connections
  with outdated security" · Unencrypted connections — "Block connections anyone on the
  network can read" (+ list of recent ones) · Public IP check — "Ask a server for your
  public address".
- **HTTPS inspection:** §5.7. Consent dialog unchanged; confirmation on every switch-on
  (D5).
- **Analysis:** Security analysis · Statistics · IT report · Certificate checks · App
  fingerprints · Packet capture · Alerts · Security systems · Night report · Ad privacy ·
  Ads & trackers detail.
- **Diagnostics:** Network diagnostics · App integrity · Blocklist details (per list: last
  update, names, refresh; shows `BlocklistUpdateTimes`, read-only).

### 6.7 Onboarding (≤ 3 screens) and splash

1. **What CoreLock does** — "Blocks ads, trackers and malware on this phone. Nothing
   leaves your phone." + honest limit: "It can't block ads inside YouTube, Instagram,
   Snapchat, TikTok or Facebook."
2. **Why Android asks for VPN permission** — "CoreLock uses Android's VPN feature to check
   connections on your phone. Your traffic isn't sent to us — we have no servers." →
   "Continue" opens the system dialog.
3. **Choose a level** — the three trade-off rows, Standard preselected. Battery guidance
   appears on this screen only when the phone restricts background apps. This is where
   general battery advice lives now; Home shows a battery card only after a real system
   stop (§5.2).


Splash: Android 12+ SplashScreen through the theme (`windowSplashScreenAnimatedIcon`
with a vector), no extra dependency if `core-splashscreen` is not needed (D6). Launcher
icon: new adaptive vector icon (foreground + background + monochrome for themed icons).

---

## 7. Renamed strings (English, old → new)

Keys keep their names unless a key is removed; every change goes to `values`,
`values-ar`, `values-fr`, `values-nl` in the same phase.

### 7.1 Words banned on everyday screens

DNS, SNI, TLS, QUIC, DoH, DoT, TCP, UDP, SOCKS5, PCAP, SIEM, JA3, JA4, CA, payload,
tunnel, cleartext, resolver, packet, SDK, port. Allowed only in Advanced subtitles that
need them in brackets (e.g. "Fast web protocol (QUIC)") and in Technical details.
A unit test scans the English strings used by Home, Apps, Activity, Settings (not
Advanced) and onboarding for these words.

### 7.2 Navigation

| Key | Old | New |
|---|---|---|
| nav home | Home | Home |
| nav apps | Apps | Apps |
| nav traffic | Traffic | Activity |
| nav stats | Stats | *(removed from tab bar; screen title "Statistics")* |
| nav payload | Payload | *(removed from tab bar; screen title "HTTPS inspection")* |
| nav settings | Settings | Settings |

### 7.3 Home

| Key | Old | New |
|---|---|---|
| dash_title | Dashboard | Home |
| dash_protected | Protected | You're protected |
| dash_not_protected | Not protected | Protection is off |
| *(new)* | — | Starting… |
| *(new)* | — | Protection stopped |
| *(new)* | — | Blocking ads and trackers · %1$s |
| dash_blocked_24h | Blocked (24 h) | Blocked today |
| *(new)* | — | Something not working? |
| dash_privacy_score_n | Privacy score %1$d | (moves to Statistics, same text) |
| dash_encryption_health | Encryption health | (moves to Statistics, same text) |
| dash_throughput | Throughput | Speed (Statistics) |
| dash_security_systems | Security systems | Security systems (Analysis) |
| dash_recent_alerts | Recent alerts | Alerts (Analysis) |
| dash_recommendations | Recommendations | (becomes the attention card) |

### 7.4 Levels

| Old | New title | New description |
|---|---|---|
| Minimal (old description) | Minimal | Blocks malware. Nothing breaks. |
| Standard | Standard | Blocks ads and trackers. Recommended. |
| Strict | Strict | Blocks more. Some apps or sites may break. |

### 7.5 Settings and Advanced

| Key | Old | New title | New subtitle |
|---|---|---|---|
| set_security_rules | Security rules | *(section removed; rows move to Advanced › Network)* | — |
| set_block_cleartext | Block Cleartext (HTTP) | Unencrypted connections | Block connections anyone on the network can read |
| set_block_weak_tls | Block Weak TLS / Deny deprecated TLS 1.0 and 1.1 | Old encryption | Block connections with outdated security |
| set_block_quic | Block QUIC for better filtering | Fast web protocol (QUIC) | Block it so CoreLock can see more connections |
| set_kill_switch | Kill switch | Kill switch | On: no internet without CoreLock |
| set_auto_restart | Automatic restart after a crash | Restart after a crash | Turn protection back on by itself |
| set_doh | DNS over HTTPS / Encrypt DNS queries | Encrypted DNS | Hide which sites you look up from your network |
| set_ct | Certificate Transparency checks | Certificate checks | Warn about suspicious site certificates |
| set_external_ip | External IP check | Public IP check | Ask a server for your public address |
| set_runtime_integrity | Runtime integrity | App integrity | Check that CoreLock hasn't been changed |
| set_upstream_dns | Upstream DNS | DNS server | Which server answers lookups |
| set_diagnostics | Network diagnostics | Network diagnostics | Check what may stop protection |
| set_blocklists | Blocklists | Blocklists | Updated %1$s |
| set_data_management | Data management | Data | — |
| set_retention | Data retention | Keep history for | %1$d days |
| set_clear_data | Clear all collected data | Clear all data | (exact wording checked against the code in Phase 6) |
| set_danger_zone | Danger zone | *(removed; the destructive button and confirmation carry the warning)* | — |
| set_analysis | Analysis & insights | Analysis | Statistics, alerts and reports |
| set_nav_security | Security Analysis | Security analysis | — |
| set_nav_ads | Ads & Trackers | Ads & trackers detail | — |
| set_nav_rules | Firewall Rules | Rules and allowed sites | Sites and apps you allowed or blocked |
| set_nav_payloads | Payload Inspector | HTTPS inspection | See inside some encrypted connections |
| set_dual_vpn | Dual VPN | Use two proxy servers | Not encrypted. The first server can see where you connect. |
| set_dual_enable | Enable Dual VPN | Use two proxy servers | — |
| set_dual_block_udp | Block UDP outside the chain | Block other traffic | Stop traffic that can't go through the servers |
| *(new)* | — | Advanced | For experts. Wrong settings can break your connection. |
| *(new)* | — | Appearance / System / Light / Dark | — |
| *(new)* | — | What CoreLock can and can't do | — |
| *(new)* | — | Report a problem | — |
| "Check for updates" | Check for updates | See new versions | Opens the GitHub releases page |

### 7.6 Screen titles

| Key | Old | New |
|---|---|---|
| apps_subtitle | Risk and traffic from the last 24 h | *(removed; status is in each row)* |
| conn_title | Connections | Activity |
| conn_subtitle | Live and recent connections | *(removed)* |
| stats_title | Statistics | Statistics |
| stats_subtitle | Traffic analytics and anomaly trends | *(removed)* |
| rules_title | Rules | Rules and allowed sites |
| sec_title | Crypto analysis | Security analysis |
| ads_title | Ad blocker | Blocklists |

### 7.7 Activity, App detail and fix flow (new strings)

Blocked · Ads and trackers list · Blocked · Malware list · Blocked · Your rule · Allowed ·
Just now · Today · Yesterday · Earlier · Allow for this app · Allow everywhere · Keep
blocked · Allow anyway · Remove my rule · Block for this app · Block everywhere ·
Technical details · More about this name · Which app isn't working? · This was blocked in
the last hour · Allowed. Try the app again. · Undo · Allowed · Blocked in background ·
Blocked · Can use the internet only while you're using it. · Can't use the internet. ·
Connected recently · Built-in trackers · Share report · Sort by · Active now.

Full string-by-string tables (with ar/fr/nl) are produced per phase and collected in
`docs/TRANSLATION_REVIEW_1.1.md` (D7).

---

## 8. Decisions (owner, 2026-11-02)

| # | Decision | Owner's answer | Where it lands |
|---|---|---|---|
| D1 | **Pause protection for 5 minutes** | **Yes, as a separate change after Phase 4.** It must always resume automatically, also after the app process is killed or the phone restarts, and show a notification with a countdown and "Resume now". | Separate task after Phase 4 (behaviour change, own review). Design notes: the resume time is stored persistently; resume is scheduled and re-checked at boot and on every app or service start, so a missed alarm can never leave protection off; the notification shows a countdown and a "Resume now" action (explicit, immutable `PendingIntent`, nothing exported). The fix flow (§6.2) leaves room for the button. |
| D2 | **Export / import of rules and settings** | **Yes, a separate task with its own ADR after the redesign.** Warn that the export file is private (it contains the user's rules). Import reuses the existing parser safety limits. | ADR after Phase 8. Export text: "This file contains your rules and settings. Keep it private." Import reuses the existing config-import limits and validation; no second parser. |
| D3 | **"Allow for this app" for DNS-layer blocks** | **Agreed.** Offer "Allow everywhere" with: "This was blocked before the app connected, so it can only be allowed for all apps." | §6.2 detail sheet and fix flow. |
| D4 | **Appearance (System / Light / Dark)** | **Yes, default System.** | One UI-only preference. Theme support in Phase 2; the Settings row in Phase 6. |
| D5 | **Confirm every time HTTPS inspection is turned on** | **Yes.** Note it for the HTTPS inspection security review. | Phase 6. The Phase 6 report has a separate "HTTPS inspection security review" section: this confirmation, the unchanged consent dialog, and that nothing outside the app or MDM can turn inspection on. |
| D6 | **Screenshot or splash libraries** | **Agreed, no new dependencies.** | Screenshots from the debug catalog on the emulator; splash through platform theme attributes. |
| D7 | **Translation review file** | **Yes, `docs/TRANSLATION_REVIEW_1.1.md`.** | Created in Phase 2, extended every phase. |
| D8 | **Allow/Block per app + destination** | **Yes, ViewModel helper only, with a test.** | Phases 4–5. The test checks the created rule through `NameBlockPolicy` and that it uses `HIGH_PRIORITY`. |
| D9 | **Plain risk word per app** | **Yes, existing data only.** Exact mapping in the spec; explain the word when tapped. | §6.3.1. |
| D10 | **Chrome fingerprint alert severity** | **Agreed, separate fix outside the redesign.** | Not on this branch. |
| D11 | **"Report a problem"** | **Yes: open the browser on the GitHub issue templates.** Confirm the network disclosure stays correct. | §5.6 S35. Checked 2026-11-02: `OutboundHostsDisclosureTest` covers only requests the app makes itself (its `senders`: `BlocklistSource`, `ExodusUpdater`, `CtMonitor`, `SettingsViewModel`, `DnsHandler`). A link the user opens in the browser is not such a request, like today's releases-page link and `lic_repo_url` (`strings_licenses.xml:27`). The README table "Network requests the app makes" stays correct. To keep it so, the URL goes in a non-translatable string resource, not in `SettingsViewModel.kt`. Phase 6 re-runs the test. |
| D12 | **Stats and Payload leave the tab bar** | **Yes, in the 1.1 release notes,** plus a one-time "What's new: some things moved" card after the update, with links to Statistics and HTTPS inspection. | §6.1 Home, parity rows R20 and H12; release notes in Phase 8. |

## 9. Phase plan and tests

| Phase | Builds | Main tests |
|---|---|---|
| 2 | Theme (light/dark), tokens, type, components with previews, debug catalog; screenshots light / dark / Arabic / 200 % | `ContrastTokensTest`, `ThemeTokenUsageTest` (with allow-list), component Compose tests (48 dp targets, labels) |
| 3 | Home + level sheet | Hero state at first frame (no false "off"), tap counts: protected 0, toggle 1, level 2; "Blocked today" equals `BlockStats` |
| 4 | Activity, detail sheet, fix flow | Fix a broken site in 3 taps; rules created with `HIGH_PRIORITY`; malware warning shown before allowing; Undo deletes the rule |
| 5 | Apps + App detail | Block an app in 2 taps; Usage Access step; no package name in the list |
| 6 | Settings + Advanced, Payload moved | Navigation parity test over every row of §5 and every deep-link alias in §4.3 |
| 7 | Onboarding, splash, icon | Onboarding ≤ 3 screens; limits text present |
| 8 | Polish: accessibility, the 8 `while(true)` loops → Flow, idle CPU before/after on Home and Activity (> 10 % of a core is a blocker), one count source test, translations, banned-word test, allow-list in `ThemeTokenUsageTest` empty | All of the above; baseline to keep: 761 unit tests, 0 failures; lint 0 errors |

The eight polling loops (AlertInboxViewModel `:43`, AppDetailViewModel `:177`,
ConnectionsViewModel `:155`, DashboardViewModel `:132` and `:147`, SettingsViewModel
`:77`, StatisticsViewModel `:112` and `:120`) are replaced in the phase that rewrites
their screen, and Phase 8 checks none is left.
