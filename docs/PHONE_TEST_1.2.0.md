# Phone test for 1.2.0: networks, vendors and battery

Checks that only a real phone on a real network can answer, added for 1.2 (hardening plan,
task 0.4). 1.1.0 was never published, so users update from the published **1.0.0** straight
to 1.2.0. Run check 0 below first (the update path), then `docs/PHONE_TEST_1.1.0.md` from
its section 2 on, with the 1.2.0 APK in place of 1.1.0 (its section 1 is replaced by check
0), then checks A to G.

**Who:** the owner, or a tester with the phones below. **Build:** the signed release APK
that will be published, nothing else. Package: `com.privacyguard.app.enterprise`.

## How to use this checklist

- Same rules as the 1.1.0 test: steps in order, every result in the table at the end
  (PASS, FAIL, or SKIP with a reason), a **blocker** FAIL stops the release.
- Commands run on the computer with the phone on USB. Always pass `-s PHONE_ID` (the
  serial from `adb devices`), so no command reaches another device or an emulator.
- Some checks need a particular phone or network. If you don't have it, write SKIP and
  what was missing; do not guess a result.

| Check | Needs |
|---|---|
| A | Mobile data on an IPv6-only network with 464XLAT (common on T-Mobile US, many EU and Asian carriers) |
| B | Any phone, Android 9 or newer (Private DNS) |
| C | A Samsung phone and a Xiaomi (or Redmi/POCO) phone |
| D | Any phone, two ordinary days |
| E | A Wi-Fi network with a login page (hotel, café, train, airport) |
| F | Any phone |
| G | Any phone (do it right after check 0, and once more at the end) |

---

### 0. Install the published 1.0.0, then update to 1.2.0

1. Download the APK attached to the published **v1.0.0** release on GitHub and save it as
   `release-1.0.0.apk`. Check both APKs before installing:
   ```
   certutil -hashfile release-1.0.0.apk SHA256
   certutil -hashfile CoreLock-1.2.0.apk SHA256
   apksigner verify --print-certs release-1.0.0.apk
   apksigner verify --print-certs CoreLock-1.2.0.apk
   ```
   **Expected:** the 1.0.0 SHA-256 equals the one on the v1.0.0 release page; the 1.2.0
   SHA-256 equals the one in `RELEASE_NOTES_1.2.0.md`; both certificate digests equal the
   fingerprint in the README.
2. Start from 1.0.0. If any CoreLock or PrivacyGuard build is on the phone, uninstall it
   first (this deletes its data): `adb -s PHONE_ID uninstall com.privacyguard.app.enterprise`.
   Then: `adb -s PHONE_ID install release-1.0.0.apk`. **Expected:** the app is called
   PrivacyGuard and Settings › About shows 1.0.0.
3. Give the update something to keep: go through onboarding, start protection at
   **Standard**, let the lists download (Settings › Blocklists shows a count), browse for a
   few minutes, add one Block rule (`example.net`) and one per-app Allow rule, change one
   setting (for example notifications off). If you test HTTPS inspection: turn it on with
   consent and install its certificate ("PrivacyGuard Enterprise CA").
4. Note the time, then update: `adb -s PHONE_ID install -r CoreLock-1.2.0.apk`.
   **Expected:** `Success`, without uninstalling. The app is now called **CoreLock**,
   Settings › About shows 1.2.0, no onboarding, and the rules, the setting, the level and
   the history from step 3 are all still there.
5. Turn protection on (if Android's Always-on VPN restarted it, skip to check G and note
   that). **Expected:** Home says protected; the one-time "Some things moved" card shows
   once. With inspection from step 3: Home shows the "Install certificate" card until the
   new "CoreLock HTTPS Inspection CA" is installed.

**Fail (blocker):** install refused, onboarding shown again, or any rule, setting or
history lost.

### G. Time until filtering starts (new in 1.2)

CoreLock lets no traffic through until its lists are loaded. It keeps a quick-start copy of
the lists for every level. The **first start after updating from 1.0.0** reads 1.0.0's
older copy once and checks every name like a download; a **level changed while protection
is off** uses that level's copy. Without any usable copy it reads the encrypted database
first. Measured on the test emulator with a debug build (2026-11-07): after the update
7.5–8.0 s (9.8 s before this change), after a level change 1.8–2.1 s (9.7–10.0 s before),
a normal start about 2 s, the database path about 10 s. The phone, with the release build,
decides.

1. Copy the probe to the phone once:
   `adb -s PHONE_ID push tools/repro/startup-dns-wait.sh /data/local/tmp/`
2. **First start after the update** (check 0, step 5): before turning protection on, run
   `adb -s PHONE_ID shell sh /data/local/tmp/startup-dns-wait.sh`, then turn protection on
   within 10 s. Wait for the line `RESULT: lookups waited N s`.
3. **Level changed while off:** turn protection off, switch the level to **Strict**, run
   the probe again and turn protection on. Then switch back to Standard the same way.
4. **Normal start:** turn protection off and on again without changing anything, with the
   probe running.
5. Also by hand once, at step 2 or 3: count the seconds from tapping the switch until a
   site you haven't opened today loads in Chrome.

Before you trust a RESULT, run the probe once with protection **already on** for a minute.
If it reports slow lookups there too, the upstream resolver is slow or loses packets on
this network (2026-11-10, Belgian ISP: about half of all lookups to 1.1.1.1 and 1.0.0.1
took 6–12 s, also from a computer without CoreLock). RESULT then measures that, not the
start. Use the time from the tap to the first fast lookup instead.

Also note: if protection was on before the update, CoreLock turns it back on by itself
about 3 s after `install -r` finishes (BootReceiver, MY_PACKAGE_REPLACED), before you can
open the app. To measure step 2 with the probe, start the probe **before** `install -r`.
If you missed it, take the time from the log (`adb -s PHONE_ID logcat -d`, from the
process start to the end of the start-up list read) and say so in the table.

**Pass:** filtering active within **30 s** in steps 2, 3 and 5 (RESULT 30 s or less), and
within 5 s in step 4. **Fail (blocker):** more than 30 s in any step. Write every number in
the table, with the phone model.

### A. IPv6-only mobile data with 464XLAT

On these networks the phone has only an IPv6 address from the carrier; Android adds a small
translator (CLAT) so apps that need IPv4 still work. CoreLock must not break that.

1. Wi-Fi off, mobile data on. Check that the network is IPv6-only with CLAT:
   ```
   adb -s PHONE_ID shell ip -o addr | grep -E "clat|v4-"
   adb -s PHONE_ID shell ip -o -4 addr | grep -v " lo "
   ```
   **Expected:** the first command shows an interface named `v4-…` or `clat…`; the second
   shows no IPv4 address on the mobile interface (`rmnet…`), only on `v4-…` and `tun0`.
   If there is no `v4-`/`clat` interface, this network is not 464XLAT: write SKIP.
2. Protection on at **Standard**. In Chrome open five sites, including one known to be
   IPv4-only (most smaller news or shop sites; check on the computer with
   `nslookup -type=AAAA <site>`: no AAAA answer means IPv4-only).
   **Expected:** every site loads; ads from separate ad servers are gone.
3. A WhatsApp (or Signal) **voice and video call**, 2 minutes each.
   **Expected:** both stay up, with audio and video both ways.
4. An app that talks to an IPv4 literal address, if you have one (some games, some
   banking apps). **Expected:** works as without CoreLock.
5. Settings › Advanced › Diagnostics. **Expected:** the IPv6 line counts only blocked
   packets (an increase during the test is normal); no error states.

**Fail (blocker):** a site, call or app works without CoreLock but not with it on this
network.

### B. Private DNS (Android 9+)

Private DNS set to a hostname makes Android send every lookup as DNS over TLS (port 853)
to that host. CoreLock blocks DNS over TLS (layer 2), and in hostname mode Android does not
fall back, so names stop resolving while protection runs. The app must say so before it
happens (README, First setup step 3).

1. Android Settings › Network › Private DNS › **Private DNS provider hostname**,
   enter `dns.google`, save. Open CoreLock.
   **Expected:** Home shows the Private DNS warning, with a way to open the setting.
2. Stop and start protection. **Expected:** the warning appears before protection
   starts, not only afterwards ("Private DNS will stop DNS from working", with "Start
   anyway" and "Open network settings").
3. Tap "Start anyway", then open a site you haven't opened today in Chrome.
   **Expected:** it does not load (lookups fail), and Home still shows the warning.
4. Set Private DNS to **Automatic**. **Expected:** the warning is gone within a few seconds
   (no restart), pages load normally, ads are blocked as usual.
5. Set Private DNS to **Off**. **Expected:** the same as Automatic.

**Fail:** no warning in step 1 or 2, a warning that stays in step 4, or pages that do not
load in Automatic or Off (steps 4 and 5).

### C. Screen off on Samsung and Xiaomi

Some vendors stop background apps more aggressively than Android itself. Run each step on
**both** phones, with the vendor's default battery setting for CoreLock (do not exempt it).

1. Protection on at Standard. Note the time. Screen off, phone untouched, **60 minutes**
   (on the charger is fine; do it off the charger once if you can).
2. Unlock. **Expected:** the CoreLock notification is still there, Home says protected,
   and a site loads at once.
3. Check that the service really ran the whole time:
   ```
   adb -s PHONE_ID shell dumpsys activity services com.privacyguard.app.enterprise | grep -E "PrivacyVpnService|createTime|lastActivity"
   adb -s PHONE_ID shell pidof com.privacyguard.app.enterprise
   ```
   **Expected:** the service is listed, with a `createTime` before step 1.
4. Repeat steps 1–3 with the screen off for a **night** (6 hours or more).
5. Samsung: Settings › Battery › Background usage limits: note whether CoreLock is in
   "Sleeping apps" or "Deep sleeping apps". Xiaomi: Settings › Apps › CoreLock › Battery
   saver: note the mode. **Expected:** not put to (deep) sleep by default; if it is, note
   it and check that Settings › Diagnostics › Battery shows the problem.
6. If protection stopped in any step: **Expected:** the notification and Home say so
   (never "protected" while it isn't), and turning on Always-on VPN with "Block
   connections without VPN" blocks traffic until it comes back.

**Fail (blocker):** Home or the notification says protected while traffic goes past
CoreLock. **Fail (not a blocker):** the vendor stops the service; record it for the
docs, with the vendor's setting that prevents it.

### D. 24-hour battery comparison

One day without CoreLock and one day with it, used the same way, so CoreLock's real cost
can be told apart from Android's habit of charging a VPN for other apps' traffic.

1. **Day 1 (baseline):** protection off, and CoreLock not set as Always-on VPN. Charge to
   100 %, unplug, then reset the counters:
   ```
   adb -s PHONE_ID shell dumpsys batterystats --reset
   ```
   Use the phone normally for 24 hours (or until 20 % left). Then save:
   ```
   adb -s PHONE_ID shell dumpsys batterystats --charged > day1.txt
   ```
   Write down: battery % used, hours, screen-on time (Settings › Battery).
2. **Day 2 (CoreLock at Standard):** the same as day 1 with protection on, on a similar
   day (the same networks, similar screen-on time). Save `day2.txt` the same way.
3. Compare, per hour of screen-on time and per hour overall:
   ```
   grep -E "Estimated power use|Screen on|Uid u0a" day1.txt | head -20
   grep -E "Estimated power use|Screen on|Uid u0a" day2.txt | head -20
   ```
   and CoreLock's own line in day 2 (`adb -s PHONE_ID shell dumpsys package com.privacyguard.app.enterprise | grep userId`
   gives its uid, `u0a…`).
4. Write both days in the table: % used, hours, screen-on hours, % per hour.

**Fail:** day 2 drains more than **5 percentage points** more per 24 hours than day 1 at
similar use, or CoreLock's own share is above 10 % of the day (the 1.1.0 rule). A
difference this small is noisy: if it fails by a little, repeat both days before
deciding.

### E. Captive portal (login page Wi-Fi)

1. Protection on at **Strict**, with Always-on VPN and "Block connections without VPN"
   **on** (the hardest case). Connect to the Wi-Fi.
2. **Expected:** Android shows "Sign in to network"; tapping it opens the login page;
   the login works.
3. After logging in: **Expected:** sites load through CoreLock within about 10 seconds;
   ads are blocked.
4. Repeat with Always-on VPN off. **Expected:** the same.

**Fail (blocker):** the login page does not open or does not work while CoreLock is on,
and works with it off. Write which network (hotel name, train operator) so it can be
checked again.

### F. Secure connections that can't be read (new in 1.2)

At Standard and Strict, CoreLock now resets a secure connection whose start it cannot read
(it used to let it through unchecked). Ordinary browsers and apps always send a readable
start, so nothing should break.

1. Protection on at **Standard**. For 30 minutes use Chrome (ten sites, a login, a video),
   YouTube, WhatsApp, a banking app and two apps you use daily.
   **Expected:** everything works as at 1.0.0 (or with protection off).
2. Settings › Advanced › Diagnostics › *Server-name blocking (SNI)*: note the second number
   ("Reset because their TLS start could not be read").
   **Expected:** 0, or a handful at most. If it is more than 10 after 30 minutes, note which
   app was in use: that app is sending something unusual and needs a look before release.
3. Repeat step 1 for 10 minutes at **Strict**. **Expected:** the same.
4. Switch to **Minimal**. **Expected:** the number no longer grows.

**Fail (blocker):** an app or site works at 1.0.0 (or with protection off) and stops working at Standard in 1.2.0.

---

## Results

Phones (model, Android version, vendor UI version): ________
APK SHA-256 (first 8): ________ Dates: ________

| # | Check | Result (PASS / FAIL / SKIP) | Numbers and notes |
|---|---|---|---|
| 0 | Checksums and fingerprint; published 1.0.0, then 1.2.0 over it, data kept; cards once | | |
| G | Filtering active within 30 s: first start after update, level changed while off, normal start | | After update: ___ s Strict: ___ s Standard: ___ s Normal: ___ s By hand: ___ s |
| A | IPv6-only + 464XLAT: sites (one IPv4-only), voice and video call, IPv4-literal app | | Carrier: ___ CLAT interface: ___ |
| B | Private DNS: warning on Home and before start, lookups fail in hostname mode as warned, warning gone and pages load in Automatic and Off | | |
| C | Screen off 60 min and one night, Samsung and Xiaomi, true status if stopped | | Samsung: ___ Xiaomi: ___ |
| D | Battery, day without vs day with CoreLock | | Day 1: ___ % / ___ h (screen ___ h) Day 2: ___ % / ___ h (screen ___ h) |
| E | Captive portal at Strict with lockdown, and without | | Network: ___ |
| F | Unreadable-TLS resets at Standard and Strict, nothing broken | | Count after 30 min: ___ |
