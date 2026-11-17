# Phone test for 1.2.1

The check of the signed 1.2.1 release on a real phone, before it is published. Users
update from the published **1.2.0**, so the test starts there. It covers what tests and
the emulator cannot: the update path with real data, the 1.2.1 changes on a real phone,
real apps, real networks and real battery.

This checklist is complete on its own. It is built from the 1.2.0 test and the 1.1.0
two-day checklist (both in [docs/archive/testing/](../archive/testing/)), with the steps
for 1.2.1 added in section 2.

**Who:** the owner, or a tester with the phones below. **Build:** the signed release APK
that will be published (`CoreLock-1.2.1.apk`), nothing else (no debug build: a debug
build is not signed with the release key and blocks sensitive actions). Package:
`com.privacyguard.app.enterprise`.

## How to use this checklist

- Do the steps in order. Each step says what to expect and when it **fails**.
- Write every result in the table at the end: PASS, FAIL, or SKIP with a reason.
- A FAIL marked **blocker** stops the release until it is fixed. Other FAILs go to 1.2.2,
  unless you decide otherwise.
- Commands run on the computer, with the phone on USB and USB debugging on. Replace
  `PHONE_ID` with the serial that `adb devices` shows, and always pass `-s PHONE_ID`, so
  no command reaches the emulator or another device.
- Commands are written for Git Bash. In PowerShell, replace `grep` with `Select-String`.
- Some checks need a particular phone or network. If you don't have it, write SKIP and
  what was missing; do not guess a result.

| Section | Needs |
|---|---|
| 0, 1, 2, 3 | Any phone; day 1 |
| 4 | Any phone; two ordinary days (battery) |
| A | Mobile data on an IPv6-only network with 464XLAT |
| B | Any phone, Android 9 or newer (Private DNS) |
| C | A Samsung phone and a Xiaomi (or Redmi/POCO) phone |
| E | A Wi-Fi network with a login page (hotel, café, train, airport) |

---

## 0. Install the published 1.2.0, then update to 1.2.1

1. Download the APK attached to the published **v1.2.0** release on GitHub and save it as
   `CoreLock-1.2.0.apk`. Check both APKs before installing:
   ```
   certutil -hashfile CoreLock-1.2.0.apk SHA256
   certutil -hashfile CoreLock-1.2.1.apk SHA256
   apksigner verify --print-certs CoreLock-1.2.0.apk
   apksigner verify --print-certs CoreLock-1.2.1.apk
   ```
   **Expected:** the 1.2.0 SHA-256 is
   `299663f8e7119656630572c3e67d7c22b1a7153b0bebb19de4a9c3acca10b26e` (README, Install);
   the 1.2.1 SHA-256 equals the one in
   [RELEASE_NOTES_1.2.1.md](../releases/RELEASE_NOTES_1.2.1.md); for both,
   "Signer #1 certificate SHA-256 digest" equals the fingerprint in the README
   (Security). **Fail (blocker):** any value differs.
2. Start from 1.2.0. If any CoreLock or PrivacyGuard build is on the phone, uninstall it
   first (this deletes its data): `adb -s PHONE_ID uninstall com.privacyguard.app.enterprise`.
   Then: `adb -s PHONE_ID install CoreLock-1.2.0.apk`. **Expected:** `Success`;
   Settings › About shows 1.2.0.
3. Give the update something to keep: go through onboarding, start protection at
   **Standard**, let the lists download (Settings › Blocklists shows a count), browse for a
   few minutes. Then in Settings › Rules and allowed sites add:
   - a Block rule for the site `example.com`;
   - an Allow rule for one site you use;
   - and change one setting (for example Appearance › Light).

   Check that `www.example.com` still opens in Chrome: in 1.2.0 a Block rule matches only
   the exact name. If you test HTTPS inspection: turn it on with consent and install its
   certificate.
4. Start the probe of check G (step G.2) now if you want to measure the first start, then
   update: `adb -s PHONE_ID install -r CoreLock-1.2.1.apk`.
   **Expected:** `Success`, without uninstalling. Settings › About shows 1.2.1; no
   onboarding; the level, both rules, the setting and the history from step 3 are all
   still there. If protection was on, CoreLock turns it back on by itself about 3 s after
   the install.
5. Open Home. **Expected:** Home says protected (or one tap turns it on).

**Fail (blocker):** install refused, onboarding shown again, or any rule, setting or
history lost.

### G. Time until filtering starts

CoreLock lets no traffic through until its lists are loaded. 1.2.0 already wrote a
quick-start copy of the lists for every level, so the first start after this update
should be as fast as a normal start. On the emulator (debug build, 2026-11-07) a normal
start took about 2 s, a level changed while off about 2 s, and the database path (no
usable copy) about 10 s. The phone, with the release build, decides.

1. Copy the probe to the phone once:
   `adb -s PHONE_ID push tools/repro/startup-dns-wait.sh /data/local/tmp/`
2. **First start after the update:** start
   `adb -s PHONE_ID shell sh /data/local/tmp/startup-dns-wait.sh` before
   `install -r` (step 0.4), because CoreLock restarts itself after the update. Wait for
   the line `RESULT: lookups waited N s`. If you missed it, take the time from the log
   (`adb -s PHONE_ID logcat -d`, from the process start to the end of the start-up list
   read) and say so in the table.
3. **Level changed while off:** turn protection off, switch the level to **Strict**, run
   the probe and turn protection on within 10 s. Then switch back to Standard the same way.
4. **Normal start:** turn protection off and on again without changing anything, with the
   probe running.
5. Also by hand once: count the seconds from tapping the switch until a site you haven't
   opened today loads in Chrome.

Before you trust a RESULT, run the probe once with protection **already on** for a minute.
If it reports slow lookups there too, the upstream resolver is slow on this network (seen
on 2026-11-10: about half of all lookups to 1.1.1.1 took 6–12 s, also without CoreLock).
RESULT then measures that, not the start; use the time from the tap to the first fast
lookup instead.

**Pass:** filtering active within **30 s** in steps 2, 3 and 5, and within 5 s in step 4.
**Fail (blocker):** more than 30 s in any step.

---

## 1. The app after the update

1. **Four tabs.** Home, Apps, Activity, Settings each open without a crash.
2. **One "blocked today" number.** Compare it in the notification, on Home, at the top of
   Activity › Blocked, in Statistics and in Settings › Advanced › Analysis › Security
   systems, within a minute. **Expected:** the same number everywhere (a block that
   arrives in between can add one).
3. **Blocklists.** Settings › Blocklists: a count and "Updated …". Compare with
   [BLOCKING_POLICY.md](../BLOCKING_POLICY.md) §3: about 287,000 names at Standard and
   about 342,000 at Strict (counted 2026-10-28; lists change). Check the refresh jobs:
   ```
   adb -s PHONE_ID shell dumpsys jobscheduler | grep -A3 "com.privacyguard.app.enterprise" | grep -i -E "blocklist|period|network|battery"
   ```
   **Expected:** one weekly job that needs an unmetered network (`blocklist_update`) and
   one daily job on any network (`blocklist_update_stale`), each exactly once. Tap
   **Refresh now**: no list drops to a small number. **Fail:** a list stays at 0 or at
   the built-in fallback after 10 minutes on a working network.
4. **Kill switch screen.** Settings › Kill switch shows a status (on / off / not checked)
   and an **Open VPN settings** button, no switch. With Always-on VPN and *Block
   connections without VPN* on and protection running, it reads on; with *Block
   connections without VPN* off, off; with protection stopped, not checked. Turn Always-on
   VPN off again afterwards (pause, in step 6, is not offered under Always-on). **Fail
   (blocker):** it says on while Android's setting is off.
5. **Per-app rules.** Pick a test app with network (for example a weather app). Apps ›
   that app › Internet access › **Blocked**: the app has no network and its connections
   show as blocked in Activity. **Blocked in background** (needs Usage Access): it works
   while open; a minute after you leave it, its background connections show as blocked.
   Set it back to **Allowed**: it works again without restarting protection.
6. **"Something not working?" and Pause.** Home › Something not working? › pick an app ›
   pick a blocked name › **Allow for this app**: at most 3 taps; the rule appears in
   Settings › Rules and allowed sites; **Undo** removes it. Then **Pause protection for 5
   minutes** (Android may first ask for "Alarms & reminders"), swipe CoreLock away from
   recent apps and wait. **Expected:** protection is back by itself at the time shown.
   **Fail (blocker):** protection stays off.
7. **Export and import** (needs a screen lock). Settings › Data › Export rules and
   settings asks for the screen lock, then saves through Android's file picker. Delete one
   rule, import the file: the sheet shows "1 new rule" and nothing changes before
   **Import**. **Fail (blocker):** the file is written without the lock prompt.
8. **Language and text size.** Settings › Language › العربية: the whole app is
   right-to-left and nothing is cut off on Home, Activity, Apps, Settings, Advanced and
   Diagnostics (the 1.2.1 counters are long). Then Android's largest font size: the tab
   labels are readable and nothing overlaps. Set both back.
9. **HTTPS inspection asks first** (only if you use it). Settings › Advanced › HTTPS
   inspection › switch on: a question appears; Cancel leaves it off.

---

## 2. New in 1.2.1

### 2.1 A Block rule now covers subdomains

1. Protection on. In Chrome open `https://www.example.com` (step 0.3 left a Block rule
   for `example.com` from 1.2.0).
   **Expected:** it does not load; Activity shows it blocked, and its details say a rule
   of yours stopped it. **Fail (blocker):** it loads.
2. Settings › Rules and allowed sites › **Add rule** › Site › `www.example.com` ›
   **Allow** › **Add**. Open `https://www.example.com` again.
   **Expected:** it loads (the longer Allow wins), while `https://example.com` stays
   blocked.
3. Add an Allow rule for a plain name, for example `example.org`, and a Block rule for
   `*.example.org`. **Expected:** `example.org` loads; `www.example.org` is blocked (an
   Allow rule still matches only the exact name).
4. Delete the test rules afterwards.

### 2.2 The notifications-off card

1. Android Settings › Apps › CoreLock › Notifications: turn all notifications off. Open
   CoreLock.
   **Expected:** Home shows "Notifications are off. If protection stops, CoreLock can't
   warn you." It can't be dismissed. Its button opens Android's notification settings for
   CoreLock.
2. Turn notifications back on and return to CoreLock.
   **Expected:** the card is gone within a few seconds, without restarting the app.
3. Turn only the alerts channel off (Android's notification categories for CoreLock), if
   your phone shows them. **Expected:** the card comes back; turn the channel on again.

**Fail:** no card while notifications are off, or a card that stays after they are on.

### 2.3 "Protection stopped" after a crash

Settings › Restart after a crash must be on, with "Alarms & reminders" allowed (the
setting asks for it). Wait at least 3 minutes between crashes: Android treats an app that
crashed again within about 2 minutes as "crashed quickly" and may refuse to restart it.

1. Protection on, Always-on VPN **off**. Crash the app:
   `adb -s PHONE_ID shell am crash com.privacyguard.app.enterprise`
   **Expected:** "Protection stopped" appears at once; the "Protection on" notification
   is gone within a second; within about 10 s protection is back by itself and the
   "Protection stopped" notice disappears. Write the number of seconds.
2. Crash again within a minute of step 1 (Android may now refuse the restart).
   **Expected:** if protection does not come back, "Protection stopped" **stays up**, and
   tapping it starts protection again. Home never says protected while it isn't.
3. Wait 3 minutes. Turn on Always-on VPN with *Block connections without VPN*. Crash once
   more and, during the restart, reload a site in Chrome.
   **Expected:** the site does not load until protection is back (Android blocks it),
   then loads. Turn Always-on VPN off again afterwards.
4. Turn notifications off (2.2) and crash once. **Expected:** no notice (that is what the
   card warns about); Home shows the true state when you open it. Turn notifications on.

**Fail (blocker):** Home or the notification says protected while it isn't, or the
"Protection stopped" notice is missing in step 1 or 2 while notifications are on.

### 2.4 Unencrypted websites and open connections

1. Protection on at Standard. Open `http://neverssl.com` in Chrome: it loads. Add a Block
   rule for the site `neverssl.com` and reload at once.
   **Expected:** it does not load. In Activity its details say *Site name in an
   unencrypted request* when Chrome still had the address cached, or *DNS* when it looked
   the name up again (the lookup is blocked first); both are a pass. Delete the rule.
2. Start something long from one site (a large download, or a video on a site you can
   spare for a minute). While it runs, add a Block rule for that site's name.
   **Expected:** it stops within a few seconds, without restarting protection. Delete the
   rule and check that it works again.

### 2.5 Connection limits don't break normal use

1.2.1 caps the connections it tracks (4,096 TCP, 4,096 UDP and 1,024 per app at once).
Normal use stays far below these limits.

1. At **Standard**, for 30 minutes: Chrome with 20 or more tabs open at once (news sites
   with many ads), a 2-minute video call, a speed test (any speed-test app or site),
   YouTube, a game or app that downloads a lot, and a cloud backup or app-store update if
   one is due.
   **Expected:** everything works as with 1.2.0.
2. Settings › Advanced › Diagnostics › *Connection safety limits*.
   **Expected:** "New connections refused because too many were open" is **0**; "Idle
   connections closed early" is 0 or small; "Broken-up packets dropped" is small (note
   it). Also note *Connections from an unknown app* and *Handled errors*.
3. Repeat step 1 for 10 minutes at **Strict**. **Expected:** the same.

**Fail (blocker):** any connection refused at the limit during normal use, or an app that
works with 1.2.0 and fails with 1.2.1. Note the app and the counters.

---

## 3. Daily use, networks and memory (day 1)

### 3.1 Daily use at Standard and Strict

At **Standard**, at least 4 hours of normal use, including:

| App | What to do | Expected |
|---|---|---|
| Chrome | A news site, a Google search, log in to any site | Pages load; fewer ads; login works |
| YouTube | Play three videos, search | Videos play (in-app YouTube ads may still show: [BLOCKING_POLICY.md](../BLOCKING_POLICY.md) §6) |
| WhatsApp | Messages, a photo, a voice call, a **video call** (at least 2 min) | Everything works; audio and video stay up |
| Banking app | Log in, view balance | Works (if it refuses the VPN, note it) |
| An app that showed ads | Use it 5 minutes | Fewer or no ads from separate ad servers |

Then at **Strict** for at least 2 hours, the same apps. Strict may break features that
depend on telemetry; note each one. Chrome's Safe Browsing warning must still work: open
`https://testsafebrowsing.appspot.com/s/phishing.html` in Chrome. **Expected:** the red
warning page.

**Fail (blocker):** a video call fails or drops repeatedly; a banking login or a normal
site that works without CoreLock breaks at Standard; no Safe Browsing warning at Strict.

### 3.2 Secure connections that can't be read

1. After an hour at Standard, Settings › Advanced › Diagnostics › *Server-name blocking
   (SNI)*: note the second number ("Reset because their TLS start could not be read").
   **Expected:** 0, or a handful at most. More than 10 per hour: note which app was in
   use.
2. Switch to **Minimal** for 10 minutes. **Expected:** the number no longer grows.

### 3.3 Networks, airplane mode, reboot

1. Switch Wi-Fi → mobile data → Wi-Fi, three times, with Chrome open.
   **Expected:** pages load again within about 10 seconds; protection stays on.
2. Airplane mode on for 1 minute, then off. **Expected:** the same.
3. Reboot. **Expected:** with Always-on VPN, protection starts by itself; without it,
   the app does not claim protection until you start it.

### 3.4 Memory and CPU

With protection on at **Strict** and the app in the background, in the morning and the
evening:
```
adb -s PHONE_ID shell dumpsys meminfo com.privacyguard.app.enterprise | grep -E "TOTAL PSS|Java Heap|Native Heap"
```
Write both numbers down; they are still missing from the docs (CLAUDE.md §6.2).
**Fail:** above 250 MB, or growing by more than 50 MB between morning and evening.

For Home, Activity and Statistics: open the screen, leave the phone untouched with the
screen on, and run:
```
PID=$(adb -s PHONE_ID shell pidof com.privacyguard.app.enterprise)
T1=$(adb -s PHONE_ID shell cat /proc/$PID/stat | awk '{print $14+$15}')
sleep 60
T2=$(adb -s PHONE_ID shell cat /proc/$PID/stat | awk '{print $14+$15}')
echo "CPU % of one core: $(( (T2 - T1) * 100 / 6000 ))"
```
**Fail (blocker):** above **10 %** of one core on Home or Activity.

---

## 4. Battery (two days)

One day without CoreLock and one day with it, used the same way, so CoreLock's real cost
can be told apart from Android's habit of charging a VPN for other apps' traffic.

1. **Day 1 (baseline):** protection off, no Always-on VPN. Charge to 100 %, unplug, then
   `adb -s PHONE_ID shell dumpsys batterystats --reset`. Use the phone normally for 24
   hours (or until 20 % left), then
   `adb -s PHONE_ID shell dumpsys batterystats --charged > day1.txt`.
   Write down: % used, hours, screen-on time.
2. **Day 2:** the same with protection on at Standard, on a similar day. Save `day2.txt`.
3. Compare per hour, and CoreLock's own line in day 2
   (`adb -s PHONE_ID shell dumpsys package com.privacyguard.app.enterprise | grep userId`
   gives its uid).

**Fail:** day 2 drains more than **5 percentage points** more per 24 hours than day 1 at
similar use, or CoreLock's own share is above 10 % of the day. If it fails by a little,
repeat both days before deciding.

---

## Checks on other phones and networks

### A. IPv6-only mobile data with 464XLAT

1. Wi-Fi off, mobile data on. Check that the network is IPv6-only with CLAT:
   ```
   adb -s PHONE_ID shell ip -o addr | grep -E "clat|v4-"
   adb -s PHONE_ID shell ip -o -4 addr | grep -v " lo "
   ```
   **Expected:** an interface named `v4-…` or `clat…`, and no IPv4 address on the mobile
   interface (`rmnet…`). Otherwise this network is not 464XLAT: write SKIP.
2. Protection on at Standard. Open five sites in Chrome, including one IPv4-only site
   (`nslookup -type=AAAA <site>` on the computer gives no AAAA answer).
3. A WhatsApp (or Signal) voice and video call, 2 minutes each.

**Fail (blocker):** a site or call works without CoreLock but not with it.

### B. Private DNS (Android 9+)

1. Android Settings › Network › Private DNS › **Private DNS provider hostname**: enter
   `dns.google`. Open CoreLock. **Expected:** Home warns, with a way to open the setting.
2. Stop and start protection. **Expected:** the warning appears before protection starts,
   with "Start anyway" and "Open network settings".
3. "Start anyway", then open a new site. **Expected:** it does not load, as warned.
4. Set Private DNS to **Automatic**, then **Off**. **Expected:** the warning is gone within
   a few seconds and pages load normally.

**Fail:** no warning in step 1 or 2, or pages that do not load in Automatic or Off.

### C. Screen off on Samsung and Xiaomi

On **both** phones, with the vendor's default battery setting for CoreLock:

1. Protection on at Standard. Screen off, untouched, **60 minutes**; then once for a
   **night** (6 hours or more).
2. Unlock. **Expected:** the notification is still there, Home says protected, a site
   loads at once.
   ```
   adb -s PHONE_ID shell dumpsys activity services com.privacyguard.app.enterprise | grep -E "PrivacyVpnService|createTime"
   ```
   **Expected:** the service is listed, with a `createTime` from before the screen went
   off.
3. Note the vendor's battery mode for CoreLock (Samsung: Sleeping / Deep sleeping apps;
   Xiaomi: Battery saver). If protection stopped: the notification and Home say so.

**Fail (blocker):** Home or the notification says protected while traffic goes past
CoreLock. **Fail (not a blocker):** the vendor stops the service; record the setting
that prevents it.

### E. Captive portal

1. Protection on at **Strict**, Always-on VPN and *Block connections without VPN* on.
   Connect to the Wi-Fi. **Expected:** "Sign in to network" opens the login page and the
   login works; afterwards sites load within about 10 seconds.
2. Repeat with Always-on VPN off. **Expected:** the same.

**Fail (blocker):** the login works with CoreLock off but not with it on. Write which
network.

---

## Results

Phones (model, Android version, vendor UI version): ________
APK SHA-256 (first 8): ________ Dates: ________

| # | Check | Result (PASS / FAIL / SKIP) | Numbers and notes |
|---|---|---|---|
| 0 | Checksums and fingerprint; published 1.2.0, then 1.2.1 over it, data kept | | |
| G | Filtering active: first start after update, level changed while off, normal start | | After update: ___ s Strict: ___ s Normal: ___ s By hand: ___ s |
| 1 | After the update: tabs, one blocked-today number, blocklists and jobs, kill switch, per-app rules, fix flow and pause, export/import, Arabic and large text, inspection asks | | Standard: ___ names Strict: ___ names |
| 2.1 | Block rule covers subdomains; longer Allow wins; Allow stays exact | | |
| 2.2 | Notifications-off card shown, opens settings, goes away | | |
| 2.3 | "Protection stopped" after a crash, restart, notice stays when refused, lockdown | | Restart after ___ s |
| 2.4 | Unencrypted site blocked by name; open connection stopped by a new rule | | |
| 2.5 | Connection limits: 0 refused in heavy normal use | | Refused: ___ Closed early: ___ Fragments: ___ |
| 3.1 | Daily use at Standard and Strict; Safe Browsing warning | | |
| 3.2 | Unreadable-TLS resets | | ___ per hour |
| 3.3 | Network switching, airplane mode, reboot | | |
| 3.4 | Memory (PSS at Strict) and CPU (blocker above 10 %) | | PSS morning ___ MB evening ___ MB; Home ___ % Activity ___ % Stats ___ % |
| 4 | Battery, day without vs day with | | Day 1: ___ % / ___ h Day 2: ___ % / ___ h |
| A | IPv6-only + 464XLAT | | Carrier: ___ |
| B | Private DNS warning and modes | | |
| C | Screen off, Samsung and Xiaomi | | Samsung: ___ Xiaomi: ___ |
| E | Captive portal with and without lockdown | | Network: ___ |

**Decision:** release 1.2.1 / fix first. Blockers found: ________
