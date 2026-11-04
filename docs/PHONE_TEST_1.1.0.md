# Phone test for 1.1.0

A two-day check of the signed 1.1.0 release on a real phone, before it is published.
The emulator checks ran earlier (`docs/ux/REDESIGN_PROGRESS.md`); this test covers what an
emulator cannot show: the update from the published 1.0.0, real apps, real networks, real
battery and real CPU, and the steps a debug build can't do (the device lock before
sensitive actions: a debug build is not signed with the release key and blocks them).

**Who:** the owner, on his own phone. **Build:** the signed release APK that will be
published (`CoreLock-1.1.0.apk`), nothing else (no debug build). On the phone the app is
called **CoreLock**; its package is `com.privacyguard.app.enterprise`. 1.0.1 was never
published, so users update from 1.0.0 straight to 1.1.0.

## How to use this checklist

- Do the steps in order. Each step says what to expect and when it **fails**.
- Write every result in the table at the end: PASS, FAIL, or SKIP with a reason.
- Any FAIL marked **blocker** stops the release until it is fixed. Other FAILs go to
  1.1.1, unless you decide otherwise.
- Commands run on the computer, with the phone connected by USB and USB debugging on.
  Replace `PHONE_ID` with the serial that `adb devices` shows. Always pass
  `-s PHONE_ID`, so a command never reaches the emulator or another device.
- Commands are written for Git Bash. In PowerShell, replace `grep` with `Select-String`.

---

## Day 1

### 1. Install 1.0.0, then update to 1.1.0

Users update from the published 1.0.0, so test exactly that path: the published 1.0.0
APK first, then 1.1.0 installed over it.

1. Download the APK attached to the published **v1.0.0** release on GitHub and save it
   as `release-1.0.0.apk`. Check both APKs before installing:
   ```
   certutil -hashfile release-1.0.0.apk SHA256
   apksigner verify --print-certs release-1.0.0.apk
   certutil -hashfile CoreLock-1.1.0.apk SHA256
   apksigner verify --print-certs CoreLock-1.1.0.apk
   ```
   **Expected:** the 1.0.0 SHA-256 equals the one in `RELEASE_NOTES_1.0.md`; the 1.1.0
   SHA-256 equals the one you will put in `RELEASE_NOTES_1.1.0.md` (`<APK_SHA256>`); for
   both, "Signer #1 certificate SHA-256 digest" equals the fingerprint in the README
   (Security). **Fail (blocker):** any value differs.
2. Start from 1.0.0. If any CoreLock or PrivacyGuard build is on the phone, uninstall
   it first (this deletes its data): `adb -s PHONE_ID uninstall com.privacyguard.app.enterprise`.
   Then: `adb -s PHONE_ID install release-1.0.0.apk`.
   **Expected:** `Success`; the app is called **PrivacyGuard**; Settings › About shows
   1.0.0.
3. Give the update something to keep: open the app, go through onboarding, accept the
   VPN request, choose **Strict**, add one Block rule (for example `example.org`), allow
   one site, and browse for a few minutes so the history has entries. Note the blocked
   count on Home.
4. Update: `adb -s PHONE_ID install -r CoreLock-1.1.0.apk`.
   **Expected:** `Success`, without uninstalling. The app is now called **CoreLock**
   (launcher, notification, Android's VPN settings); Settings › About › Version shows
   1.1.0; the level is still Strict; Settings › Rules and allowed sites shows both rules,
   each titled by its site; the history kept its entries and the blocked count did not go
   back to 0; no onboarding again; protection is on again (or one tap on Home turns it on).
   **Fail (blocker):** install refused, onboarding shown again, or any rule, setting or
   history lost.
5. The first Home after the update shows the **"Some things moved"** card once.
   **Expected:** "Open Statistics" and "Open HTTPS inspection" open those screens with a
   back arrow; after either link (or ×) the card does not come back.

### 2. First setup and the four tabs

1. **Expected:** the bar has four tabs, Home, Apps, Activity, Settings; no Stats or Payload
   tab. Each opens without a crash.
2. Private DNS: Android Settings › Network › Private DNS. Set it to a hostname (for
   example `dns.google`), then start protection again.
   **Expected:** CoreLock warns before starting and on Home. Set Private DNS back
   to **Automatic** afterwards.
3. Settings › Advanced › Diagnostics › Battery: note the state; tap the button.
   **Expected:** Android's battery settings for the app open.
4. Kill switch: Android Settings › Network › VPN › CoreLock › turn on
   **Always-on VPN** and **Block connections without VPN**.
   **Expected:** see step 6.

### 3. Blocklists

1. Settings › Blocklists, right after the update on Wi-Fi.
   **Expected:** a name count and "Updated …" within about 2 minutes. Per-list counts are
   in Settings › Advanced › Diagnostics. Write down the total.
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
4. Tap **Refresh now**. **Expected:** the count stays similar; no list drops to a small
   number.

### 4. Daily use at Standard

Set the level to **Standard** (Home › Protection level). Use the phone normally for at
least 4 hours, including:

| App | What to do | Expected |
|---|---|---|
| Chrome | A news site, a Google search, log in to any site | Pages load; fewer ads; login works |
| YouTube | Play three videos, search | Videos play (in-app YouTube ads may still show: see `docs/BLOCKING_POLICY.md`) |
| WhatsApp | Send messages, a photo, a voice call, a **video call** (at least 2 min) | Everything works; call audio and video stay up |
| Banking app | Log in, view balance | Works (if it refuses the VPN, note it) |
| Any app that showed ads before | Use it 5 minutes | Fewer or no ads from separate ad servers |

**Fail (blocker):** a video call fails or drops repeatedly; a banking login or a
normal site that worked without CoreLock breaks at Standard. Note the app, the
time and what happened; the Activity tab shows what was blocked and why.

### 5. Daily use at Strict

Set the level to **Strict**. Repeat the table of step 4 for at least 2 hours.

**Expected:** the same apps work. Strict may break features that depend on telemetry;
note each one. Chrome's Safe Browsing warnings must still work: open
`https://testsafebrowsing.appspot.com/s/phishing.html` in Chrome.
**Expected:** Chrome shows its red warning page. **Fail (blocker):** no warning.

### 6. Kill switch

1. Settings › Kill switch.
   **Expected:** a status card (on / off / not checked) and an **Open VPN settings**
   button. There is **no switch**. The button opens Android's VPN settings.
2. With Always-on and Block connections on (step 2.4) and protection running, the
   status reads on. Turn **Block connections without VPN** off: the status reads off.
   Stop protection: the status reads not checked.
3. With lockdown on, stop protection from the notification or in the app.
   **Expected:** apps have no internet until protection runs again (that is Android
   blocking). **Fail (blocker):** the screen says on while Android's setting is off,
   or a switch is drawn.
4. Turn **Always-on VPN** off again for step 10 (pause is not offered under Always-on).

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
   Internet access › **Blocked**. **Expected:** the app has no network; its
   connections show as blocked in Activity.
2. Change it to **Blocked in background** (needs Usage Access; the app asks for it).
   **Expected:** the app works while open; after you leave it for 1 minute, its
   background connections show as blocked.
3. Set it back to **Allowed** (or Undo). **Expected:** the app works again without
   restarting protection.

### 9. Language, RTL and large text

1. Settings › Language › **العربية**.
   **Expected:** the whole app is right-to-left; nothing is cut off on Home, Activity,
   Apps, Settings, Advanced and the two-proxy screen. Switch back to English.
2. Android Settings › Display › Font size: largest. **Expected:** the four tab labels
   are readable and not cut; Home, Activity and Settings scroll and nothing overlaps.
   Set the font size back.

**Fail:** text cut off, overlapping, or left-to-right in Arabic.

### 10. New in 1.1: "Something not working?" and Pause

1. Home › **Something not working?** › pick an app › pick a blocked name › **Allow for
   this app**. **Expected:** at most 3 taps; the rule appears in Settings › Rules;
   **Undo** removes it.
2. Home › Something not working? › **Pause protection for 5 minutes** (Android 12+ may
   first ask for "Alarms & reminders"). **Expected:** Home says "Protection is paused ·
   Back on at …"; a notification counts down with **Resume now**.
3. Swipe CoreLock away from recent apps. Wait 5 minutes without opening it.
   **Expected:** protection is back on by itself at the shown time; the notification
   says protected. **Fail (blocker):** protection stays off.
4. Pause again, then tap **Resume now** in the notification. **Expected:** on at once.

### 11. New in 1.1: export and import of rules and settings

This needs the signed build (a debug build blocks it, as it blocks other sensitive
actions). Set a screen lock first if the phone has none.

1. Settings › Data › **Export rules and settings**. **Expected:** a sheet says the file is
   private; **Choose where to save** asks for the screen lock, then Android's file picker
   opens; save `corelock-rules-and-settings.json`; "Rules and settings saved".
   **Fail (blocker):** the file is written without the lock prompt.
2. Delete one of your rules and change the theme.
3. Settings › Data › **Import rules and settings** › screen lock › pick the file.
   **Expected:** "Import these rules and settings?" with "1 new rule" and "1 setting will
   change"; nothing changes before **Import**; after it the rule and the theme are back.
4. Import the same file again. **Expected:** "Everything in this file is already on this
   phone."
5. If you allowed a site **despite the malware warning** before exporting: after the
   import it reads plain "Allows", not "despite the malware warning" (an import never
   carries that override).

### 12. HTTPS inspection asks first

Only if you use HTTPS inspection. Settings › Advanced › HTTPS inspection › switch on.
**Expected:** a question ("Turn on HTTPS inspection?", or the consent text the first time);
Cancel leaves it off; only **Turn on** turns it on. Switching it off asks nothing and offers
to remove the certificate.

## Day 2

### 13. Diagnostics and export

1. Settings › Advanced › Diagnostics: note the counters (blocked connections, SNI, QUIC).
   **Expected:** numbers are non-zero after a day of use and no screen shows an error.
2. Settings › Data › **Export history** (or Statistics › export CSV). Open the file on the
   computer. **Expected:** 7 days of connections; no cell starts with `=`, `+`, `-` or
   `@` unless it is escaped with a leading `'`.

### 14. Memory

With protection on at **Strict** and the app in the background:
```
adb -s PHONE_ID shell dumpsys meminfo com.privacyguard.app.enterprise | grep -E "TOTAL PSS|Java Heap|Native Heap"
```
**Expected:** TOTAL PSS roughly in line with the emulator (blocklists alone take about
44 MB at Strict). Write it down. **Fail:** above 250 MB, or growing by more than
50 MB between the morning and evening of day 2.

### 15. CPU on Home and Activity (blocker rule)

1.0 used 42 % (Home) and 68 % (Stats) of one core on the emulator with nothing happening;
1.1 measured lower on the emulator (`docs/ux/REDESIGN_PROGRESS.md`, CPU). The phone decides.

For each screen (Home, Activity, then Settings › Advanced › Analysis › Statistics): open
it, leave the phone untouched and the screen on, and run:
```
PID=$(adb -s PHONE_ID shell pidof com.privacyguard.app.enterprise)
T1=$(adb -s PHONE_ID shell cat /proc/$PID/stat | awk '{print $14+$15}')
sleep 60
T2=$(adb -s PHONE_ID shell cat /proc/$PID/stat | awk '{print $14+$15}')
echo "CPU % of one core: $(( (T2 - T1) * 100 / 6000 ))"
```
(Ticks are 1/100 s on Android, so 6000 ticks = 60 s of one core.) Repeat twice per
screen and keep the higher value. Also measure with the screen off for 60 s.

**Fail (blocker):** above **10 %** of one core on Home or Activity.

### 16. Battery

After a full day, Android Settings › Battery › Battery usage › CoreLock.
Write down the percentage. **Fail:** above 10 % of the day's battery.
Note: if CoreLock is among the top 3 apps, investigate but do not fail it for that
alone: Android often charges a VPN app for traffic that belongs to other apps.

### 17. Mobile data used by CoreLock

Android Settings › Network › Data usage › Mobile › CoreLock, over the two days.
**Expected:** the app's own downloads are small: all blocklists together are about
4 MB, fetched weekly on Wi-Fi (or after 14 days without success on mobile data).
Note: on some Android versions, the traffic CoreLock forwards for other apps is
also counted under CoreLock. If the number is large, compare it with your total
mobile data; when it roughly matches, it is the forwarded traffic, not ours.

### 18. Blocklists after two days

Settings › Blocklists. **Expected:** the count is still there and similar to day 1; no
list in Diagnostics has dropped to a small number or to the built-in fallback.

---

## Results

Phone model: ________ Android version: ________ APK SHA-256 (first 8): ________
Dates: ________

| # | Check | Result (PASS / FAIL / SKIP) | Numbers and notes |
|---|---|---|---|
| 1 | Checksums and fingerprint; 1.0.0, then 1.1.0 over it, data kept; What's new card once | | |
| 2 | Four tabs, Private DNS warning, battery screen | | |
| 3 | Blocklist counts (Standard / Strict total), refresh jobs | | Standard: ___ Strict: ___ |
| 4 | Daily use at Standard (Chrome, YouTube, WhatsApp video call, bank) | | |
| 5 | Daily use at Strict; Chrome Safe Browsing warning | | |
| 6 | Kill switch screen (no switch, true status) | | |
| 7 | Network switching, airplane mode, reboot, captive portal | | |
| 8 | Per-app block and background-only | | |
| 9 | Arabic RTL, largest font | | |
| 10 | Fix flow (≤ 3 taps, Undo); pause comes back after 5 min with the app swiped away | | |
| 11 | Export (lock prompt, private warning) and import round trip | | |
| 12 | HTTPS inspection asks before turning on | | |
| 13 | Diagnostics counters, CSV export | | |
| 14 | Memory (TOTAL PSS at Strict) | | Morning: ___ MB Evening: ___ MB |
| 15 | CPU, Home / Activity / Statistics / screen off (blocker above 10 %) | | Home: ___ % Activity: ___ % Stats: ___ % Off: ___ % |
| 16 | Battery over one day | | ___ % |
| 17 | Mobile data used by CoreLock, 2 days | | ___ MB |
| 18 | Blocklists after two days | | |

**Decision:** release 1.1.0 / fix first. Blockers found: ________
