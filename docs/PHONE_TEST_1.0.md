# Phone test for 1.0.0

A two-day check of the signed 1.0.0 release on a real phone, before it is published.
The emulator checks run earlier; this test covers what an emulator cannot show:
real apps, real networks, real battery and real CPU.

**Who:** the owner, on his own phone. **Build:** the signed release APK that will be
published, nothing else (no debug build). On the phone the app is called
**PrivacyGuard**; its package is `com.privacyguard.app.enterprise`.

## How to use this checklist

- Do the steps in order. Each step says what to expect and when it **fails**.
- Write every result in the table at the end: PASS, FAIL, or SKIP with a reason.
- Any FAIL marked **blocker** stops the release until it is fixed. Other FAILs go to
  1.0.1, unless you decide otherwise.
- Commands run on the computer, with the phone connected by USB and USB debugging on.
  Replace `PHONE_ID` with the serial that `adb devices` shows. Always pass
  `-s PHONE_ID`, so a command never reaches the emulator or another device.
- Commands are written for Git Bash. In PowerShell, replace `grep` with `Select-String`.

---

## Day 1

### 1. Install and verify

1. Check the APK before installing:
   ```
   certutil -hashfile app-enterprise-release.apk SHA256
   apksigner verify --print-certs app-enterprise-release.apk
   ```
   **Expected:** the SHA-256 equals the one in `RELEASE_NOTES_1.0.md`; "Signer #1
   certificate SHA-256 digest" equals the fingerprint in the README (Security).
   **Fail (blocker):** either value differs.
2. If an older PrivacyGuard is on the phone, note its version (Settings › About)
   and keep it: step 1.3 is then an update test.
3. Install: `adb -s PHONE_ID install -r app-enterprise-release.apk`.
   **Expected:** `Success`. When updating, your rules, level and history are still
   there afterwards. **Fail (blocker):** install refused, or data lost on update.

### 2. First setup

1. Open the app. Go through onboarding; accept the VPN request.
   **Expected:** no crash; Home shows **Protected** within a few seconds; the
   notification says protection is on.
2. Private DNS: Android Settings › Network › Private DNS. Set it to a hostname (for
   example `dns.google`), then start protection again.
   **Expected:** PrivacyGuard warns before starting and on Home. Set Private DNS back
   to **Automatic** afterwards.
3. Settings › Diagnostics › Battery use: note the state; tap the button.
   **Expected:** Android's battery settings for the app open.
4. Kill switch: Android Settings › Network › VPN › PrivacyGuard › turn on
   **Always-on VPN** and **Block connections without VPN**.
   **Expected:** see step 6.

### 3. Blocklists

1. Settings › Blocklists, right after first setup on Wi-Fi.
   **Expected:** every list shows a count within about 2 minutes. Write down the
   counts and the total.
2. Compare with `docs/BLOCKING_POLICY.md` §3: about 287,000 names at Standard and
   about 342,000 at Strict. **Fail:** a list stays at 0 or the built-in fallback after
   10 minutes on a working network.
3. Check the refresh jobs (each must appear exactly once):
   ```
   adb -s PHONE_ID shell dumpsys jobscheduler | grep -A3 "com.privacyguard.app.enterprise" | grep -i -E "blocklist|period|network|battery"
   ```
   **Expected:** one weekly job that needs an unmetered network and battery not low
   (`blocklist_update`), and one daily job on any network (`blocklist_update_stale`).
   **Fail:** missing or duplicated jobs.
4. Tap **Refresh now**. **Expected:** counts stay similar; no list drops to a small
   number.

### 4. Daily use at Standard

Set the level to **Standard** on Home. Use the phone normally for at least 4 hours,
including:

| App | What to do | Expected |
|---|---|---|
| Chrome | A news site, a Google search, log in to any site | Pages load; fewer ads; login works |
| YouTube | Play three videos, search | Videos play (in-app YouTube ads may still show: see `docs/BLOCKING_POLICY.md`) |
| WhatsApp | Send messages, a photo, a voice call, a **video call** (at least 2 min) | Everything works; call audio and video stay up |
| Banking app | Log in, view balance | Works (if it refuses the VPN, note it) |
| Any app that showed ads before | Use it 5 minutes | Fewer or no ads from separate ad servers |

**Fail (blocker):** a video call fails or drops repeatedly; a banking login or a
normal site that worked without PrivacyGuard breaks at Standard. Note the app, the
time and what happened; the Traffic screen shows what was blocked ("Stopped by").

### 5. Daily use at Strict

Set the level to **Strict**. Repeat the table of step 4 for at least 2 hours.

**Expected:** the same apps work. Strict may break features that depend on telemetry;
note each one. Chrome's Safe Browsing warnings must still work: open
`https://testsafebrowsing.appspot.com/s/phishing.html` in Chrome.
**Expected:** Chrome shows its red warning page. **Fail (blocker):** no warning.

### 6. The Kill Switch card (new in this build)

1. Home › Security systems › Kill Switch.
   **Expected:** a status line (on / off / not checked) and an **Open VPN settings**
   button. There is **no switch** on the card. The button opens Android's VPN
   settings.
2. With Always-on and Block connections on (step 2.4) and protection running, the
   status reads on. Turn **Block connections without VPN** off: the status reads off.
   Stop protection: the status reads not checked.
3. Settings › Kill Switch row: the same status, **no On/Off chip**.
4. With lockdown on, stop protection from the notification or in the app.
   **Expected:** apps have no internet until protection runs again (that is Android
   blocking). **Fail (blocker):** the card says on while Android's setting is off,
   or a switch is drawn.

### 7. Networks, airplane mode, reboot

1. Switch Wi-Fi → mobile data → Wi-Fi, three times, with Chrome open.
   **Expected:** pages load again within about 10 seconds after each switch;
   protection stays on.
2. Airplane mode on for 1 minute, then off. **Expected:** the same.
3. Reboot the phone. **Expected:** with Always-on VPN, protection starts by itself;
   without it, the app does not claim protection until you start it.
4. Captive portal (hotel, café, train Wi-Fi), if you can: **Expected:** the login
   page opens and works.

### 8. Per-app rules

1. Pick a test app with network (for example a weather app). Apps › that app ›
   **Block**. **Expected:** the app has no network; its connections show as blocked.
2. Change it to **Block in background** (needs Usage Access; the app asks for it).
   **Expected:** the app works while open; after you leave it for 1 minute, its
   background connections show as blocked.
3. Remove the rule. **Expected:** the app works again without restarting protection.

### 9. Language and RTL

Settings › Language › **العربية**.
**Expected:** the whole app is right-to-left; nothing is cut off on Home (including
the Kill Switch card: the button reads "فتح إعدادات VPN"), Traffic, Settings and the
Dual VPN screen. Switch back to English. **Fail:** text cut off, overlapping, or left-
to-right in Arabic.

## Day 2

### 10. Diagnostics and export

1. Settings › Diagnostics: note the counters (blocked connections, SNI, QUIC).
   **Expected:** numbers are non-zero after a day of use and no screen shows an error.
2. Statistics › export **CSV**. Open the file on the computer.
   **Expected:** 7 days of connections; no cell starts with `=`, `+`, `-` or `@`
   unless it is escaped with a leading `'`.

### 11. Memory

With protection on at **Strict** and the app in the background:
```
adb -s PHONE_ID shell dumpsys meminfo com.privacyguard.app.enterprise | grep -E "TOTAL PSS|Java Heap|Native Heap"
```
**Expected:** TOTAL PSS roughly in line with the emulator (blocklists alone take about
44 MB at Strict). Write it down. **Fail:** above 250 MB, or growing by more than
50 MB between the morning and evening of day 2.

### 12. CPU on Home and Stats (1.0 blocker rule)

Known item 10 in CLAUDE.md. On the emulator, Home used 42 % and Stats 68 % of one
core with nothing happening. The phone decides.

For each screen (Home, then Stats): open it, leave the phone untouched and the
screen on, and run:
```
PID=$(adb -s PHONE_ID shell pidof com.privacyguard.app.enterprise)
T1=$(adb -s PHONE_ID shell cat /proc/$PID/stat | awk '{print $14+$15}')
sleep 60
T2=$(adb -s PHONE_ID shell cat /proc/$PID/stat | awk '{print $14+$15}')
echo "CPU % of one core: $(( (T2 - T1) * 100 / 6000 ))"
```
(Ticks are 1/100 s on Android, so 6000 ticks = 60 s of one core.) Repeat twice per
screen and keep the higher value. Also measure with the screen off for 60 s.

**Fail (blocker):** above **10 %** of one core on Home or Stats.

### 13. Battery

After a full day, Android Settings › Battery › Battery usage › PrivacyGuard.
Write down the percentage. **Fail:** PrivacyGuard is in the top 3, or above 10 % of the
day's battery. (A proposed limit; adjust it if you have a better reference.)

### 14. Mobile data used by PrivacyGuard

Android Settings › Network › Data usage › Mobile › PrivacyGuard, over the two days.
**Expected:** the app's own downloads are small: all blocklists together are about
4 MB, fetched weekly on Wi-Fi (or after 14 days without success on mobile data).
Note: on some Android versions, the traffic PrivacyGuard forwards for other apps is
also counted under PrivacyGuard. If the number is large, compare it with your total
mobile data; when it roughly matches, it is the forwarded traffic, not ours.

### 15. Blocklists after two days

Settings › Blocklists. **Expected:** the counts are still there and similar to day 1;
no list has dropped to a small number or to the built-in fallback.

---

## Results

Phone model: ________ Android version: ________ APK SHA-256 (first 8): ________
Dates: ________

| # | Check | Result (PASS / FAIL / SKIP) | Numbers and notes |
|---|---|---|---|
| 1 | Checksum and signing fingerprint; install or update | | |
| 2 | First setup, Private DNS warning, battery screen | | |
| 3 | Blocklist counts (Standard / Strict total), refresh jobs | | Standard: ___ Strict: ___ |
| 4 | Daily use at Standard (Chrome, YouTube, WhatsApp video call, bank) | | |
| 5 | Daily use at Strict; Chrome Safe Browsing warning | | |
| 6 | Kill Switch card and Settings row (no switch, true status) | | |
| 7 | Network switching, airplane mode, reboot, captive portal | | |
| 8 | Per-app block and background-only | | |
| 9 | Arabic RTL, then back to English | | |
| 10 | Diagnostics counters, CSV export | | |
| 11 | Memory (TOTAL PSS at Strict) | | Morning: ___ MB Evening: ___ MB |
| 12 | CPU, Home / Stats / screen off (blocker above 10 %) | | Home: ___ % Stats: ___ % Off: ___ % |
| 13 | Battery over one day | | ___ % |
| 14 | Mobile data used by PrivacyGuard, 2 days | | ___ MB |
| 15 | Blocklists after two days | | |

**Decision:** release 1.0.0 / fix first. Blockers found: ________
